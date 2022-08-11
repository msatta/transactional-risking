/*
 * Copyright 2022 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.transactionalrisking.controllers

import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.util.Try

/**
 * This Controller represents the Transactional Risk Service which is called from two origins: externally (Third Party Software via
 * the API Platform) and internally (that is, from within MDTP); as we see here, the real Service will have
 * two sets of endpoints, one for each origin, as there may be subtle (and not-so-subtle) differences required by each
 * origin which would benefit from the separation; for example: authorisation.
 *
 * Also, there will be potentially multiple "contexts" to support; we will start with Self Assessment (SA) but we're expected
 * to eventually add supports for VAT; note that a future context may not be related to a tax regime at all - it could be some sort of other registration, for example.
 *
 * It is fully expected that the request payloads will differ according to the context; however, the response payloads will likely be the same.
 *
 * Currently, the simulator only supports the one context: Self Assessment, as that's all that's expected in 2022.
 *
 */
class TransactionalRiskingController @Inject()(
                                                val controllerComponents: ControllerComponents,
                                                val assessmentService: TransactionalRiskingService,
                                                //val integrationFrameworkService: IntegrationFrameworkService TODO not very clear
                                                //val authService: AuthService TODO may be use EnrolmentsService
                                              ) extends BaseController {

  val logger: Logger = Logger("TransactionalRiskingController")

  def internalGenerateAssessmentForSelfAssessment: Action[JsValue] = {
    Action.async(parse.json) { request: Request[JsValue] => {
      request.body.validate[AssessmentRequestForSelfAssessment].fold(
        errors => {
          Future(BadRequest(asError(errors)))
        },
        assessmentRequestForSelfAssessment => {
          assessmentService.assess(assessmentRequestForSelfAssessment, Internal).map(Json.toJson[AssessmentReport]).map(js => Ok(js))
        }
      )
    }
    }
  }

  def internalAcknowledgeAssessmentForSelfAssessment(rawId: String): Action[AnyContent] = acknowledgeAssessmentForSelfAssessment(rawId, Internal)

  def externalGenerateReportForSelfAssessment(nino: String, calculationId: UUID): Action[Unit] = generateReportForSelfAssessment(External, nino, calculationId)

  def externalAcknowledgeAssessmentForSelfAssessment(rawId: String): Action[AnyContent] = acknowledgeAssessmentForSelfAssessment(rawId, External)

  def acknowledgeAssessmentForSelfAssessment(rawId: String, origin: Origin): Action[AnyContent] = Action.async { _ => {
    logger.info(s"Received request to acknowledge assessment report: [$rawId]")
    toId(rawId)
      .map(id => assessmentService.acknowledge(AcknowledgementRequestForSelfAssessment(id), origin).map(_ => NoContent))
      .getOrElse(Future(BadRequest(asError("Please provide the ID of an Assessment Report."))))
  }
  }

  private def generateReportForSelfAssessment(origin: Origin, nino: String, calculationId: UUID): Action[Unit] = Action.async(parse.empty) { request: Request[Unit] => {
    logger.info(s"Received request to generate a report from [$origin] for NINO [$nino] and Calculation ID [$calculationId]")
    assessmentService.assess(assessmentRequestForSelfAssessment(request, origin, nino, calculationId), origin).map(Json.toJson[AssessmentReport]).map(js => Ok(js))
  }
  }

  private def getAuthorisationInfo(request: Request[_]): AuthorisationInfo =
    authService.getAuthorisationInfo(request)

  private def assessmentRequestForSelfAssessment(request: Request[_], origin: Origin, nino: String, calculationId: UUID): AssessmentRequestForSelfAssessment = {
    val authorisationInfo = getAuthorisationInfo(request)
    val calculationInfo: CalculationInfo = getCalculationInfo(calculationId, nino)
    val preferredLanguage = if (origin == External) English else getPreferredLanguage(request)
    AssessmentRequestForSelfAssessment(
      calculationId = calculationId,
      nino = nino,
      preferredLanguage = preferredLanguage,
      customerType = authorisationInfo.customerType,
      agentRef = authorisationInfo.agentRef,
      taxYear = calculationInfo.taxYear
    )

  }

  private def getPreferredLanguage(request: Request[_]): PreferredLanguage = English

  private def getCalculationInfo(id: UUID, nino: String): CalculationInfo =
    integrationFrameworkService.getCalculationInfo(id, nino)
      .getOrElse( throw new RuntimeException(s"Unknown calculation for id [$id] and nino [$nino]"))


  private def toId(rawId: String): Option[UUID] =
    Try(UUID.fromString(rawId)).toOption

  private def asError(errors: Seq[(JsPath, Seq[JsonValidationError])]): JsObject = asError(JsError.toJson(errors))

  private def asError(json: JsObject): JsObject = Json.obj("message" -> json)

  private def asError(message: String): JsObject = Json.obj("message" -> message)


}

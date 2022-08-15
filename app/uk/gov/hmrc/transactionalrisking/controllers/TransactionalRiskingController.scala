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
import uk.gov.hmrc.transactionalrisking.model.AuthorisationInfo
import uk.gov.hmrc.transactionalrisking.model.domain.{AssessmentReport, AssessmentRequestForSelfAssessment, CalculationInfo, CustomerType, Internal, PreferredLanguage}
import uk.gov.hmrc.transactionalrisking.services.TransactionalRiskingService
import uk.gov.hmrc.transactionalrisking.services.auth.AuthService
import uk.gov.hmrc.transactionalrisking.services.eis.IntegrationFrameworkService

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
                                                val transactionalRiskingService: TransactionalRiskingService,
                                                val integrationFrameworkService: IntegrationFrameworkService,
                                                val authService: AuthService //TODO may be use EnrolmentsService
                                              ) extends BaseController {

  val logger: Logger = Logger("TransactionalRiskingController")

  /*
  The Client asks TRS to generate a report.
TRS uses AS to perform the usual implicit auditing.
TRS uses AS to record explicit event(s) related to this request for generation.
TRS asks IS for a "fraud risk report".
IS returns this report to TRS.
TRS then sends some representation of the original request and original fraud report to RDS.
RDS returns the assessment to TRS, which is reformatted.
TRS calls NRS to record this Assessment Requested.
NRS returns to TRS a Submission ID representing this record.
TRS uses AS to record explicit event(s) related to this assessment request, including the Submission ID.
TRS returns a 200 with a JSON representation of the report.
   */

  def generateReportInternal(nino: String, calculationId: String) = Action.async { implicit request =>

    // val report = Future(Ok("Report"))
    val customerType = deriveCustomerType(request)
    toId(calculationId).map { calculationIdUuid =>
      val calculationInfo = getCalculationInfo(calculationIdUuid, nino)
      //val report = connector.generateReport(nino, calculationId).map(g => Ok(g.message))
      //TODO fix me later, hardcoded request
      val assessmentRequestForSelfAssessment = new AssessmentRequestForSelfAssessment(calculationIdUuid,
        nino,
        PreferredLanguage.English,
        customerType,
        None,
        calculationInfo.taxYear)

      Future(
        transactionalRiskingService.assess(assessmentRequestForSelfAssessment, Internal)
          .map(Json.toJson[AssessmentReport])
          .map(js => Ok(js))
      ).flatten
    }.getOrElse(Future(BadRequest(asError("Please provide the ID of an Assessment Report."))))
  }

  private def deriveCustomerType(request: Request[AnyContent]) = {
    //TODO fix me, write logic to derive customer type
    CustomerType.TaxPayer
  }



  //  def internalGenerateAssessmentForSelfAssessment: Action[JsValue] = {
  //    Action.async(parse.json) { request: Request[JsValue] => {
  //      request.body.validate[AssessmentRequestForSelfAssessment].fold(
  //        errors => {
  //          Future(BadRequest(asError(errors)))
  //        },
  //        assessmentRequestForSelfAssessment => {
  //          transactionalRiskingService.assess(assessmentRequestForSelfAssessment, Internal).map(Json.toJson[AssessmentReport]).map(js => Ok(js))
  //        }
  //      )
  //    }
  //    }
  //  }

  //  def internalAcknowledgeAssessmentForSelfAssessment(rawId: String): Action[AnyContent] = acknowledgeAssessmentForSelfAssessment(rawId, Internal)
  //
  //  def acknowledgeAssessmentForSelfAssessment(rawId: String, origin: Origin): Action[AnyContent] = Action.async { _ => {
  //    logger.info(s"Received request to acknowledge assessment report: [$rawId]")
  //    toId(rawId)
  //      .map(id => transactionalRiskingService.acknowledge(AcknowledgementRequestForSelfAssessment(id), origin).map(_ => NoContent))
  //      .getOrElse(Future(BadRequest(asError("Please provide the ID of an Assessment Report."))))
  //  }
  //  }

  private def toId(rawId: String): Option[UUID] =
    Try(UUID.fromString(rawId)).toOption

  private def asError(message: String): JsObject = Json.obj("message" -> message)
  //
  //  def externalGenerateReportForSelfAssessment(nino: String, calculationId: UUID): Action[Unit] = generateReportForSelfAssessment(External, nino, calculationId)
  //
  //  private def generateReportForSelfAssessment(origin: Origin, nino: String, calculationId: UUID): Action[Unit] = Action.async(parse.empty) { request: Request[Unit] => {
  //    logger.info(s"Received request to generate a report from [$origin] for NINO [$nino] and Calculation ID [$calculationId]")
  //    transactionalRiskingService.assess(assessmentRequestForSelfAssessment(request, origin, nino, calculationId), origin).map(Json.toJson[AssessmentReport]).map(js => Ok(js))
  //  }
  //  }

  //  private def assessmentRequestForSelfAssessment(request: Request[_], origin: Origin, nino: String, calculationId: UUID): AssessmentRequestForSelfAssessment = {
  //    val authorisationInfo = getAuthorisationInfo(request)
  //    val calculationInfo: CalculationInfo = getCalculationInfo(calculationId, nino)
  //    val preferredLanguage = if (origin == External) English else getPreferredLanguage(request)
  //    AssessmentRequestForSelfAssessment(
  //      calculationId = calculationId,
  //      nino = nino,
  //      preferredLanguage = preferredLanguage,
  //      customerType = authorisationInfo.customerType,
  //      agentRef = authorisationInfo.agentRef,
  //      taxYear = calculationInfo.taxYear
  //    )
  //
  //  }

  private def getCalculationInfo(id: UUID, nino: String): CalculationInfo =
    integrationFrameworkService.getCalculationInfo(id, nino)
      .getOrElse(throw new RuntimeException(s"Unknown calculation for id [$id] and nino [$nino]"))

  private def getAuthorisationInfo(request: Request[_]): AuthorisationInfo =
    authService.getAuthorisationInfo(request)

//  private def getPreferredLanguage(request: Request[_]): PreferredLanguage = English

  //  def externalAcknowledgeAssessmentForSelfAssessment(rawId: String): Action[AnyContent] = acknowledgeAssessmentForSelfAssessment(rawId, External)

//  private def asError(errors: Seq[(JsPath, Seq[JsonValidationError])]): JsObject = asError(JsError.toJson(errors))

//  private def asError(json: JsObject): JsObject = Json.obj("message" -> json)


}

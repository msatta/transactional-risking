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

import play.api.http.Status
import play.api.libs.json._
import play.api.mvc._
import uk.gov.hmrc.transactionalrisking.controllers.requestParsers.AcknowledgeRequestParser
import uk.gov.hmrc.transactionalrisking.models.domain.Internal
import uk.gov.hmrc.transactionalrisking.models.errors.ErrorWrapper
import uk.gov.hmrc.transactionalrisking.models.request.AcknowledgeReportRawData
import uk.gov.hmrc.transactionalrisking.services.eis.IntegrationFrameworkService
import uk.gov.hmrc.transactionalrisking.services.nrs.models.request.AcknowledgeReportRequest
import uk.gov.hmrc.transactionalrisking.services.rds.models.response.RdsAcknowledgementResponse
import uk.gov.hmrc.transactionalrisking.services.{EnrolmentsAuthService, TransactionalRiskingService}
import uk.gov.hmrc.transactionalrisking.utils.Logging

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

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
class AcknowledgeReportController @Inject()(
                                             val cc: ControllerComponents,
                                             requestParser: AcknowledgeRequestParser,
                                             val transactionalRiskingService: TransactionalRiskingService,
                                             val integrationFrameworkService: IntegrationFrameworkService,
                                             val authService: EnrolmentsAuthService //TODO may be use EnrolmentsService
                                              )(implicit ec: ExecutionContext) extends AuthorisedController(cc) with BaseController with Logging {
    //TODO revisit if reportId needs to be UUID instead of string? as regex validation is done anyway
    def acknowledgeReportForSelfAssessment(nino: String, reportId: String): Action[AnyContent] =
      authorisedAction(nino, nrsRequired = true).async {implicit request => {
      implicit val correlationId: String = UUID.randomUUID().toString
      logger.info(s"Received request to acknowledge assessment report: [$reportId]")


        val parsedRequest: Either[ErrorWrapper, AcknowledgeReportRequest] = requestParser.parseRequest(AcknowledgeReportRawData(nino, reportId))
        val response: Either[ErrorWrapper, Future[Int]] = parsedRequest.map(req => transactionalRiskingService.acknowledge(req,Internal))
        response match {
          case Right(value) => {
            value.map(r=> logger.info(s"RDS success response $r"))

            Future(NoContent)
          }
          case Left(value) => Future(BadRequest(Json.toJson(value)))
        }
    }
    }

  private def asError(message: String): JsObject = Json.obj("message" -> message)


}

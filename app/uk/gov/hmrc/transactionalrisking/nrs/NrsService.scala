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

package uk.gov.hmrc.transactionalrisking.nrs

import com.kenshoo.play.metrics.Metrics
import play.api.libs.json.Json
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.transactionalrisking.controllers.UserRequest
import uk.gov.hmrc.transactionalrisking.nrs.models.request.{Metadata, NrsSubmission, SearchKeys}
import uk.gov.hmrc.transactionalrisking.nrs.models.response.NrsResponse
import uk.gov.hmrc.transactionalrisking.utils.{DateUtils, HashUtil, Logging}

import java.time.OffsetDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class NrsService @Inject()(
//                            auditService: AuditService,
                           connector: NrsConnector,
                           hashUtil: HashUtil) extends Logging {
//                           override val metrics: Metrics) extends Timer with Logging { TODO include metrics later

  def submit(vatSubmission: SubmitRequest, generatedNrsId: String, submissionTimestamp: OffsetDateTime)(
    implicit request: UserRequest[_],
    hc: HeaderCarrier,
    ec: ExecutionContext,
    correlationId: String): Future[Option[NrsResponse]] = {

    val nrsSubmission = buildNrsSubmission(vatSubmission, submissionTimestamp, request)

    def audit(resp: NrsOutcome): Future[AuditResult] = resp match {
      case Left(err) => logger.info(s"left message")
//        auditService.auditEvent(
//          AuditEvents.auditNrsSubmit("submitToNonRepudiationStoreFailure",
//            NrsAuditDetail(
//              vatSubmission.vrn.toString,
//              request.headers.get("Authorization").getOrElse(""),
//              Some(generatedNrsId),
//              Some(Json.toJson(nrsSubmission)),
//              correlationId))
//        )
      case Right(resp) => logger.info(s"Right resp ")
//        auditService.auditEvent(
//          AuditEvents.auditNrsSubmit("submitToNonRepudiationStore",
//            NrsAuditDetail(
//              vatSubmission.vrn.toString,
//              request.headers.get("Authorization").getOrElse(""),
//              Some(resp.nrSubmissionId),
//              None,
//              correlationId))
//        )
    }

//    timeFuture("NRS Submission", "nrs.submission") {
//      connector.submit(nrsSubmission).map { response =>
//        audit(response)
//        response.toOption
//      }
//    }
  }

  def buildNrsSubmission(vatSubmission: SubmitRequest,
                         submissionTimestamp: OffsetDateTime,
                         request: UserRequest[_]): NrsSubmission = {

    val payloadString = Json.toJson(body).toString()
    val encodedPayload = hashUtil.encode(payloadString)
    val sha256Checksum = hashUtil.getHash(payloadString)
    val formattedDate = submissionTimestamp.format(DateUtils.isoInstantDatePattern)

    NrsSubmission(
      payload = encodedPayload,
      Metadata(
        businessId = "vat",
        notableEvent = "vat-return",
        payloadContentType = "application/json",
        payloadSha256Checksum = sha256Checksum,
        userSubmissionTimestamp = formattedDate,
        identityData = request.userDetails.identityData,
        userAuthToken = request.headers.get("Authorization").get,
        headerData = Json.toJson(request.headers.toMap.map { h => h._1 -> h._2.head }),
        searchKeys =
          SearchKeys(
            vrn = Some(vrn.vrn),
            companyName = None,
            periodKey = body.periodKey,
            taxPeriodEndDate = None
          )
      )
    )
  }


}

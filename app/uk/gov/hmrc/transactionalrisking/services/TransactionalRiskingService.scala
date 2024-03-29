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

package uk.gov.hmrc.transactionalrisking.services

import play.api.Logger
import play.api.http.Status
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transactionalrisking.config.AppConfig
import uk.gov.hmrc.transactionalrisking.controllers.UserRequest
import uk.gov.hmrc.transactionalrisking.services.rds.models.request.RdsRequest.{DataWrapper, MetadataWrapper}
import uk.gov.hmrc.transactionalrisking.models.domain.{AssessmentReport, AssessmentRequestForSelfAssessment, FraudRiskReport, FraudRiskRequest, Link, Origin, Risk}
import uk.gov.hmrc.transactionalrisking.services.cip.InsightService
import uk.gov.hmrc.transactionalrisking.services.nrs.NrsService
import uk.gov.hmrc.transactionalrisking.services.nrs.models.request.{AcknowledgeReportRequest, AssistReportGenerated, GenerarteReportRequestBody, GenerateReportRequest}
import uk.gov.hmrc.transactionalrisking.services.rds.models.request.RdsRequest
import uk.gov.hmrc.transactionalrisking.services.rds.models.response.{NewRdsAssessmentReport, RdsAcknowledgementResponse}
import uk.gov.hmrc.transactionalrisking.utils.CurrentDateTime

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}


class TransactionalRiskingService @Inject()(val wsClient: WSClient,
                                            nonRepudiationService: NrsService,
                                            appConfig: AppConfig,
                                            insightService: InsightService,
                                            currentDateTime: CurrentDateTime,
                                            //                                            val nonRepudiationService: NonRepudiationService,
                                            //                                            val auditService: AuditService
                                                                                        ) {

  val logger: Logger = Logger("TransactionalRiskingService")
  private def baseUrlForRdsAssessmentsSubmit = s"${appConfig.rdsBaseUrlForSubmit}"
  private def baseUrlToAcknowledgeRdsAssessments = s"${appConfig.rdsBaseUrlForAcknowledge}"

  //TODO move this to RDS connector
  private def assess(request: RdsRequest)(implicit ec: ExecutionContext): Future[NewRdsAssessmentReport] =
    wsClient
      //      .url(baseUrlForRdsAssessments)//TODO RDS check is this for ack
      .url(baseUrlForRdsAssessmentsSubmit)
      .post(Json.toJson(request))
      .map(response =>
        response.status match {
          case Status.OK => response.json.validate[NewRdsAssessmentReport].get
          case unexpectedStatus => throw new RuntimeException(s"Unexpected status when attempting to get the assessment report from RDS: [$unexpectedStatus]")
        }
      )

  def assess(request: AssessmentRequestForSelfAssessment,origin: Origin)(implicit hc: HeaderCarrier,
                                                                         ec: ExecutionContext,
//                                                                         logContext: EndpointLogContext,
                                                                         userRequest: UserRequest[_],
                                                                         correlationId: String): Future[AssessmentReport] = {
    logger.info("Received a request to generate an assessment ...")
//    doImplicitAuditing() // TODO: This should be at the controller level.
//    doExplicitAuditingForGenerationRequest()
    val fraudRiskReport = insightService.assess(generateFraudRiskRequest(request))
//    val fraudRiskReportStub = accessFraudRiskReport(generateFraudRiskRequest(request))

    val rdsAssessmentReportResponse: Future[AssessmentReport] = assess(generateRdsAssessmentRequest(request, fraudRiskReport))
      .map(toAssessmentReport(_,request))
      .map(assessmentReport => {
        logger.info("... returning it.")
        // TODO: Should we also audit an explicit event for actually generating the assessment?
        assessmentReport
      }
      )
     rdsAssessmentReportResponse.map{ rdsReport =>
      val submissionTimestamp = currentDateTime.getDateTime
      val nrsId = request.nino //TODO generate nrs id as per the spec
       val submitRequest = GenerateReportRequest(nrsId,GenerarteReportRequestBody(rdsReport.toString,request.calculationId.toString) )
      //Submit asynchronously to NRS //TODO fix me rdsReport.get
      nonRepudiationService.submit(submitRequest, nrsId, submissionTimestamp,AssistReportGenerated)
      rdsReport
    }

  }

  def acknowledge(request: AcknowledgeReportRequest, origin: Origin)(implicit hc: HeaderCarrier,
                                                                     ec: ExecutionContext,
                                                                     //  logContext: EndpointLogContext,
                                                                     userRequest: UserRequest[_],
                                                                     correlationId:String): Future[RdsAcknowledgementResponse] = {
    logger.info(s"${correlationId} Received request to acknowledge assessment report for Self Assessment [${request.feedbackId}]")
//    doImplicitAuditing() // TODO: This should be at the controller level.
//    auditRequestToAcknowledge(request)
    acknowledgeRds(generateRdsAcknowledgementRequest(request)).map(rdsReport => {

logger.info(s"rds ack response is ${rdsReport.toString}")
        val submissionTimestamp = currentDateTime.getDateTime
        val nrsId = request.nino //TODO generate nrs id as per the spec
        val submitRequest = GenerateReportRequest(nrsId,GenerarteReportRequestBody(rdsReport.toString,request.feedbackId.toString) )
      logger.info(s"... submitting acknowledgement to NRS with body $submitRequest")
        //Submit asynchronously to NRS //TODO fix me rdsReport.get
        nonRepudiationService.submit(submitRequest, nrsId, submissionTimestamp,AssistReportGenerated)
        logger.info("... returning.")
        rdsReport
      }
    )

  }

  private def acknowledgeRds(request: RdsRequest)(implicit hc: HeaderCarrier,
                                                  ec: ExecutionContext): Future[RdsAcknowledgementResponse] =
    wsClient
      .url(baseUrlToAcknowledgeRdsAssessments)
      .post(Json.toJson(request))
      .map(response =>
        response.status match {
          case Status.OK => {logger.info(s"... submitting acknowledgement to RDS success")
          //no need to validate as we are interested only in OK response.if validation is required then
          // we need separate class, as the structure is different
            response.json.validate[RdsAcknowledgementResponse].getOrElse(throw new RuntimeException("failed to validate "))}
          case unexpectedStatus => {
            logger.error(s"... error during rds acknowledgement ")
            throw new RuntimeException(s"Unexpected status when attempting to mark the report as acknowledged with RDS: [$unexpectedStatus]")}
        }
      )

  private def toAssessmentReport(report: NewRdsAssessmentReport,request:AssessmentRequestForSelfAssessment) = {
   //TODO check should this be calculationId or feedbackId?
    AssessmentReport(reportId=report.calculationId,
      risks=risks(report),nino=request.nino,taxYear = request.taxYear,
      calculationId = request.calculationId)
  }

  private def risks(report: NewRdsAssessmentReport): Seq[Risk] =
    report.outputs
      .filter(_.isInstanceOf[NewRdsAssessmentReport.MainOutputWrapper])
      .map(_.asInstanceOf[NewRdsAssessmentReport.MainOutputWrapper])
      .flatMap(_.value)
      .filter(_.isInstanceOf[NewRdsAssessmentReport.DataWrapper])
      .map(_.asInstanceOf[NewRdsAssessmentReport.DataWrapper])
      .flatMap(_.data)
      .map(toRisk)


  private def toRisk(riskParts: Seq[String]) = Risk(title=riskParts(0), body=riskParts(1), action=riskParts(2), links=Seq(Link(riskParts(3), riskParts(4))),path=riskParts(5))

  private def generateRdsAssessmentRequest(request: AssessmentRequestForSelfAssessment, fraudRiskReport: FraudRiskReport): RdsRequest
  = RdsRequest(
    Seq(
      RdsRequest.InputWithString("calculationId", request.calculationId.toString),
      RdsRequest.InputWithString("nino", request.nino),
      RdsRequest.InputWithString("taxYear", request.taxYear),
      RdsRequest.InputWithString("customerType", request.customerType.toString),
      RdsRequest.InputWithString("agentRef", request.agentRef.getOrElse("")),
      RdsRequest.InputWithString("preferredLanguage", request.preferredLanguage.toString),
      RdsRequest.InputWithString("fraudRiskReportDecision", fraudRiskReport.decision.toString),
      RdsRequest.InputWithInt("fraudRiskReportScore", fraudRiskReport.score),
      RdsRequest.InputWithObject("fraudRiskReportHeaders",
        Seq(
          MetadataWrapper(
            Seq(
              Map("KEY" -> "string"),
              Map("VALUE" -> "string")
            )),
          DataWrapper(fraudRiskReport.headers.map(header => Seq(header.key, header.value)).toSeq)
        )
      ),
      RdsRequest.InputWithObject("fraudRiskReportWatchlistFlags",
        Seq(
          MetadataWrapper(
            Seq(
              Map("NAME" -> "string")
            )),
          DataWrapper(fraudRiskReport.watchlistFlags.map(flag => Seq(flag.name)).toSeq)
        )
      )
    )
  )

  private def generateRdsAcknowledgementRequest(request: AcknowledgeReportRequest): RdsRequest
  = RdsRequest(
    Seq(
      RdsRequest.InputWithString("feedbackId", request.feedbackId),
      RdsRequest.InputWithString("nino", request.nino)
    )
  )

//TODO Revisit Check headers as pending
  private def generateFraudRiskRequest(request: AssessmentRequestForSelfAssessment): FraudRiskRequest = {
    val fraudRiskHeaders = Map.empty[String, String]
    new FraudRiskRequest(
      request.nino,
      request.taxYear,
      fraudRiskHeaders
    )
  }



}






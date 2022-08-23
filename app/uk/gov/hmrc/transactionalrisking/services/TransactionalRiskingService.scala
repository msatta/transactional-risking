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
import uk.gov.hmrc.transactionalrisking.models.domain.{AssessmentReport, AssessmentRequestForSelfAssessment, FraudRiskReport, FraudRiskRequest, Link, Origin, Risk}
import uk.gov.hmrc.transactionalrisking.services.cip.InsightService
import uk.gov.hmrc.transactionalrisking.services.nrs.NrsService
import uk.gov.hmrc.transactionalrisking.services.nrs.models.request.{AssistReportGenerated, SubmitRequest, SubmitRequestBody}
import uk.gov.hmrc.transactionalrisking.services.rds.models.response.NewRdsAssessmentReport
import uk.gov.hmrc.transactionalrisking.utils.CurrentDateTime
import uk.gov.hmrc.transactionalriskingsimulator.services.ris.RdsAssessmentRequestForSelfAssessment
import uk.gov.hmrc.transactionalriskingsimulator.services.ris.RdsAssessmentRequestForSelfAssessment.{DataWrapper, MetadataWrapper}

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

    val rdsAssessmentReportResponse: Future[AssessmentReport] = assess(toNewRdsAssessmentRequestForSelfAssessment(request, fraudRiskReport))
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
       val submitRequest = SubmitRequest(nrsId,SubmitRequestBody(rdsReport.toString,request.calculationId.toString) )
      //Submit asynchronously to NRS //TODO fix me rdsReport.get
      nonRepudiationService.submit(submitRequest, nrsId, submissionTimestamp,AssistReportGenerated)
      rdsReport
    }

  }

//  def acknowledge(request: AcknowledgementRequestForSelfAssessment, origin: Origin): Future[Unit] = {
//    logger.info(s"Received request to acknowledge assessment report for Self Assessment [${request.assessmentId}]")
//    doImplicitAuditing() // TODO: This should be at the controller level.
//    auditRequestToAcknowledge(request)
//    acknowledge(RdsAcknowledgementRequestForSelfAssessment(request.assessmentId)).map(_ => {
//      val submissionId = nonRepudiationService.recordAsAcknowledged(NrsAcknowledgementRequestForSelfAssessment(request.assessmentId))
//      auditRecordedAcknowledgement(request, submissionId)
//      logger.info("... returning.")
//    })
//  }

/*  def find(id: UUID): Future[Option[AssessmentReport]] =
    wsClient
//      .url(s"$baseUrlForRdsAssessments/$id")
      .url(s"$baseUrlForRdsAssessmentsSubmit/$id")
      .get().map(response =>
      response.status match {
        case Status.OK => Some(response.json.validate[NewRdsAssessmentReport].get)
        case Status.NOT_FOUND => None
        case unexpectedStatus => throw new RuntimeException(s"Unexpected status when attempting to get the assessment report from RDS: [$unexpectedStatus]")
      }
    ).map(opt => opt.map(toAssessmentReport))*/

/*  private def accessFraudRiskReport(request: FraudRiskRequest)(implicit ec: ExecutionContext): Future[FraudRiskReport] =
    wsClient
      .url(baseUrlForCip)
      .post(Json.toJson(request))
      .map(response =>
        response.status match {
          //          case Status.OK => response.json.validate[FraudRiskReport].get
          case Status.OK => response.json.as[FraudRiskReport]
          case unexpectedStatus => throw new RuntimeException(s"Unexpected status when attempting to get the assessment report from RDS: [$unexpectedStatus]")
        }
      )*/

  //TODO move this to RDS connector
  private def assess(request: RdsAssessmentRequestForSelfAssessment)(implicit ec: ExecutionContext): Future[NewRdsAssessmentReport] =
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

//  private def baseUrlForRdsAssessments = s"http://localhost:$port/rds/assessments/sa"
//  private def baseUrlForRdsAssessments = s"${appConfig.rdsBaseUrlForSubmit}"
  private def baseUrlForRdsAssessmentsSubmit = s"${appConfig.rdsBaseUrlForSubmit}"
//  private def baseUrlForCip = s"${appConfig.cipFraudServiceBaseUrl}"

//  private def baseUrlForAcknowledgedRdsAssessments = s"http://localhost:$port/rds/acknowledged_assessments/sa"


//  private def acknowledge(request: RdsAcknowledgementRequestForSelfAssessment): Future[Unit] =
//    wsClient
//      .url(baseUrlForAcknowledgedRdsAssessments)
//      .post(Json.toJson(request))
//      .map(response =>
//        response.status match {
//          case Status.NO_CONTENT =>
//          case unexpectedStatus => throw new RuntimeException(s"Unexpected status when attempting to mark the report as acknowledged with RDS: [$unexpectedStatus]")
//        }
//      )

//  private def port: String = System.getProperty("http.port", "9000")


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

  private def toNewRdsAssessmentRequestForSelfAssessment(request: AssessmentRequestForSelfAssessment, fraudRiskReport: FraudRiskReport): RdsAssessmentRequestForSelfAssessment
  = RdsAssessmentRequestForSelfAssessment(
    Seq(
      RdsAssessmentRequestForSelfAssessment.InputWithString("calculationId", request.calculationId.toString),
      RdsAssessmentRequestForSelfAssessment.InputWithString("nino", request.nino),
      RdsAssessmentRequestForSelfAssessment.InputWithString("taxYear", request.taxYear),
      RdsAssessmentRequestForSelfAssessment.InputWithString("customerType", request.customerType.toString),
      RdsAssessmentRequestForSelfAssessment.InputWithString("agentRef", request.agentRef.getOrElse("")),
      RdsAssessmentRequestForSelfAssessment.InputWithString("preferredLanguage", request.preferredLanguage.toString),
      RdsAssessmentRequestForSelfAssessment.InputWithString("fraudRiskReportDecision", fraudRiskReport.decision.toString),
      RdsAssessmentRequestForSelfAssessment.InputWithInt("fraudRiskReportScore", fraudRiskReport.score),
      RdsAssessmentRequestForSelfAssessment.InputWithObject("fraudRiskReportHeaders",
        Seq(
          MetadataWrapper(
            Seq(
              Map("KEY" -> "string"),
              Map("VALUE" -> "string")
            )),
          DataWrapper(fraudRiskReport.headers.map(header => Seq(header.key, header.value)).toSeq)
        )
      ),
      RdsAssessmentRequestForSelfAssessment.InputWithObject("fraudRiskReportWatchlistFlags",
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

//TODO Revisit Check headers as pending
  private def generateFraudRiskRequest(request: AssessmentRequestForSelfAssessment): FraudRiskRequest = {
    val fraudRiskHeaders = Map.empty[String, String]
    new FraudRiskRequest(
      request.nino,
      request.taxYear,
      fraudRiskHeaders
    )
  }

//  private def doExplicitAuditingForGenerationRequest(): Unit = {
//    auditService.auditExplicit(generateExplicitAuditingRequest("Request received to generate an assessment report"))
//  }
//
//  private def doImplicitAuditing(): Unit = {
//    auditService.auditImplicit(generateImplicitAuditingRequest())
//  }
//
//
//  private def auditRequestToAcknowledge(request: AcknowledgementRequestForSelfAssessment): Unit = {
//    auditService.auditExplicit(generateExplicitAuditingRequest(s"Request received to acknowledge an assessment report for Self Assessment [${request.assessmentId}]"))
//  }
//
//  private def auditRecordedAcknowledgement(request: AcknowledgementRequestForSelfAssessment, submissionId: UUID): Unit = {
//    auditService.auditExplicit(generateExplicitAuditingRequest(s"Assessment report [${request.assessmentId}] for Self Assessment, has been recorded as acknowledged, with submission ID: [$submissionId]"))
//  }
//
//  private def generateExplicitAuditingRequest(description: String): ExplicitAuditingRequest
//  = ExplicitAuditingRequest(ExplicitAuditingEvent(description))


//  private def generateImplicitAuditingRequest(): ImplicitAuditingRequest
//  = ImplicitAuditingRequest(ImplicitAuditingEvent())


}





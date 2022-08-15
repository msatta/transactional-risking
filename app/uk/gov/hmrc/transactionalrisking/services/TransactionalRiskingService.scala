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
import uk.gov.hmrc.transactionalrisking.model.domain.{AssessmentReport, AssessmentRequestForSelfAssessment, FraudDecision, FraudRiskReport, FraudRiskRequest, Link, Origin, Risk}
import uk.gov.hmrc.transactionalrisking.rds.models.response.NewRdsAssessmentReport
import uk.gov.hmrc.transactionalrisking.services.nrs.NrsService
import uk.gov.hmrc.transactionalriskingsimulator.services.ris.RdsAssessmentRequestForSelfAssessment
import uk.gov.hmrc.transactionalriskingsimulator.services.ris.RdsAssessmentRequestForSelfAssessment.{DataWrapper, MetadataWrapper}

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future


class TransactionalRiskingService @Inject()(val wsClient: WSClient,
                                            val nonRepudiationService: NrsService
                                            //                                            val fraudRiskService: InsightService,
                                            //                                            val nonRepudiationService: NonRepudiationService,
                                            //                                            val auditService: AuditService
                                                                                        ) {

  val logger: Logger = Logger("TransactionalRiskingService")

  //TODO  Fix me, create an entity to hold these info

  def assess(request: AssessmentRequestForSelfAssessment,origin: Origin): Future[AssessmentReport] = {
    logger.info("Received a request to generate an assessment ...")
//    doImplicitAuditing() // TODO: This should be at the controller level.
//    doExplicitAuditingForGenerationRequest()
//    val fraudRiskReport = fraudRiskService.assess(generateFraudRiskRequest(request))
    //TODO fix me later by calling the above service
    val fraudRiskReport = FraudRiskReport(FraudDecision.Accept, 1, Set.empty, Set.empty)
    assess(toNewRdsAssessmentRequestForSelfAssessment(request, fraudRiskReport))
      .map(toAssessmentReport)
      .map(assessmentReport => {
        logger.info("... returning it.")
        // TODO: Should we also audit an explicit event for actually generating the assessment?
        assessmentReport
      }
      )
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

  def find(id: UUID): Future[Option[AssessmentReport]] =
    wsClient
      .url(s"$baseUrlForRdsAssessments/$id")
      .get().map(response =>
      response.status match {
        case Status.OK => Some(response.json.validate[NewRdsAssessmentReport].get)
        case Status.NOT_FOUND => None
        case unexpectedStatus => throw new RuntimeException(s"Unexpected status when attempting to get the assessment report from RDS: [$unexpectedStatus]")
      }
    ).map(opt => opt.map(toAssessmentReport))

  private def assess(request: RdsAssessmentRequestForSelfAssessment): Future[NewRdsAssessmentReport] =
    wsClient
      .url(baseUrlForRdsAssessments)
      .post(Json.toJson(request))
      .map(response =>
        response.status match {
          case Status.OK => response.json.validate[NewRdsAssessmentReport].get
          case unexpectedStatus => throw new RuntimeException(s"Unexpected status when attempting to get the assessment report from RDS: [$unexpectedStatus]")
        }
      )

  private def baseUrlForRdsAssessments = s"http://localhost:$port/rds/assessments/sa"

  private def baseUrlForAcknowledgedRdsAssessments = s"http://localhost:$port/rds/acknowledged_assessments/sa"


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

  private def port: String = System.getProperty("http.port", "9000")


  private def toAssessmentReport(report: NewRdsAssessmentReport) = {
    AssessmentReport(report.calculationId, risks(report))
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


  private def toRisk(riskParts: Seq[String]) = Risk(riskParts(1), riskParts(2), Seq(Link(riskParts(3), riskParts(4))))

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






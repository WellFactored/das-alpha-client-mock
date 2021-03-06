package controllers

import javax.inject.Inject

import actions.ClientUserAction
import cats.data.OptionT
import cats.instances.future._
import data._
import models.EmployerDetail
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.validation._
import play.api.mvc._
import services.LevyApiService

import scala.concurrent.{ExecutionContext, Future}

class ClientController @Inject()(oauth2Controller: OAuth2Controller, claims: SchemeClaimOps, stash: TokenStashOps, userAction: ClientUserAction, levy: LevyApiService)(implicit exec: ExecutionContext) extends Controller {

  private[controllers] def unclaimed(claims: Seq[SchemeClaimRow], userId: UserId): Constraint[String] = Constraint[String]("already claimed") { empref =>
    def alreadyClaimed(claim: SchemeClaimRow): Boolean = claim.empref.trim() == empref.trim()

    def claimedByUser(claim: SchemeClaimRow): Boolean = alreadyClaimed(claim) && claim.userId == userId

    empref match {
      case _ if claims.exists(claimedByUser) => Invalid(ValidationError(s"you have already claimed scheme $empref"))
      case _ if claims.exists(alreadyClaimed) => Invalid(ValidationError(s"another user has already claimed scheme $empref"))
      case _ => Valid
    }
  }

  private[controllers] def claimMapping(claimedSchemes: Seq[SchemeClaimRow], userId: UserId) =
    Form("empref" -> nonEmptyText.verifying(unclaimed(claimedSchemes, userId)))

  def index = Action(Redirect(controllers.routes.ClientController.showSchemes()))

  def showSchemes = userAction.async { request =>
    claims.forUser(request.user.id).map { claimedSchemes =>
      Ok(views.html.showSchemes(claimMapping(claimedSchemes, request.user.id), request.user, claimedSchemes))
    }
  }

  def claimScheme = userAction { implicit request => oauth2Controller.startOauthDance }

  def selectSchemes(ref: Int) = userAction.async { implicit request =>
    val validTokens = stash.peek(ref).map(_.filter(_.userId == request.user.id))
    val employerDetails = validTokens.flatMap(tokens => Future.sequence(tokens.map(token => findEmployerDetails(token.empref, token.accessToken))))
    val statusF = employerDetails.flatMap(details => checkStatuses(request.user.id, details))

    statusF.map(statuses => Ok(views.html.selectSchemes(request.user, statuses, ref)))
  }

  def linkScheme(empref: String, ref: Int) = userAction.async { implicit request =>
    def buildClaimRow(token: StashedTokenDetails, status: SchemeStatus) = status match {
      case Unclaimed(detail) if detail.empref == empref => Some(SchemeClaimRow(empref, request.user.id, token.accessToken, token.validUntil, token.refreshToken))
      case _ => None
    }

    val f = for {
      token <- OptionT(stash.peek(ref).map(_.find(_.userId == request.user.id)))
      employerDetails <- OptionT.liftF(findEmployerDetails(token.empref, token.accessToken))
      status <- OptionT.liftF(checkStatus(request.user.id, employerDetails))
      claimRow <- OptionT.fromOption(buildClaimRow(token, status))
      _ <- OptionT.liftF(claims.insert(claimRow))
      _ <- OptionT.liftF(stash.drop(ref))
    } yield claimRow


    f.value.map {
      case Some(_) => Redirect(controllers.routes.ClientController.showSchemes())
      case None => NotFound
    }
  }

  def findEmployerDetails(empref: String, accessToken: AccessToken)(implicit rh: RequestHeader): Future[EmployerDetail] =
    levy.employerDetails(empref, accessToken) map {
      case Left(s) => throw new Error(s)
      case Right(d) => d
    }

  def checkStatuses(userId: UserId, details: Seq[EmployerDetail]): Future[Seq[SchemeStatus]] = Future.sequence {
    details.map(checkStatus(userId, _))
  }

  def checkStatus(userId: UserId, detail: EmployerDetail): Future[SchemeStatus] = claims.forEmpref(detail.empref) map {
    case None => Unclaimed(detail)
    case Some(claim) if claim.userId == userId => UserClaimed(detail)
    case Some(claim) => OtherClaimed(detail)
  }

  def removeScheme(empref: String) = userAction.async { implicit request =>
    claims.removeClaimForUser(empref, request.user.id).map { count =>
      Redirect(controllers.routes.ClientController.index())
    }
  }
}


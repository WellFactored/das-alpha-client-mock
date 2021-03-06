package controllers

import javax.inject.Inject

import actions.ClientUserAction
import cats.data.EitherT
import cats.instances.future._
import cats.syntax.either._
import data._
import play.api.mvc._
import services.ServiceConfig.config
import services.{LevyApiService, OAuth2Service}

import scala.concurrent.{ExecutionContext, Future}

class OAuth2Controller @Inject()(oAuth2Service: OAuth2Service, accessTokens: TokenStashOps, schemes: SchemeClaimOps, api: LevyApiService, userAction: ClientUserAction)(implicit exec: ExecutionContext) extends Controller {

  def startOauthDance(implicit request: RequestHeader): Result = {
    val params = Map(
      "client_id" -> Seq(config.client.id),
      "redirect_uri" -> Seq(routes.OAuth2Controller.claimCallback(None, None, None, None, None).absoluteURL(config.client.useSSL)),
      "scope" -> Seq("read:apprenticeship-levy"),
      "response_type" -> Seq("code")
    )
    Redirect(config.api.authorizeSchemeUri, params)
  }

  case class TokenDetails(accessToken: AccessToken, validUntil: Long, refreshToken: RefreshToken)

  def claimCallback(code: Option[String], state: Option[String], error:Option[String], errorDescription:Option[String], errorCode:Option[String]) = userAction.async { implicit request =>
    val tokenDetails: Future[Either[Result, TokenDetails]] = code match {
      case None => Future.successful(Left(BadRequest("No oAuth code")))
      case Some(c) => convertCodeToToken(c).map(Right(_))
    }

    val refx = for {
      td <- EitherT(tokenDetails)
      emprefs <- EitherT(api.root(td.accessToken).map(_.leftMap(BadRequest(_))))
      ds = emprefs.emprefs.map(StashedTokenDetails(_, td.accessToken, td.validUntil, td.refreshToken, request.user.id))
      ref <- EitherT[Future, Result, Int](accessTokens.stash(ds).map(Right(_)))
    } yield ref

    refx.value.map {
      case Left(result) => result
      case Right(ref) => Redirect(controllers.routes.ClientController.selectSchemes(ref))
    }
  }

  def convertCodeToToken(c: String): Future[TokenDetails] = for {
    atr <- oAuth2Service.convertCode(c)
    validUntil = System.currentTimeMillis() + (atr.expires_in * 1000)
  } yield TokenDetails(atr.access_token, validUntil, atr.refresh_token)
}


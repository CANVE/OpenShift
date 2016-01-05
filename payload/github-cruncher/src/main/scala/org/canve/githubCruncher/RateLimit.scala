package org.canve.githubCruncher
import scala.concurrent.{Future, Promise}
import scala.util.{Success, Failure}
import scala.concurrent.ExecutionContext.Implicits.global
import scalaj.http._
import com.github.nscala_time.time.Imports._
import scala.concurrent.Await

object RateLimitedApiCaller {
  
  private var lastKnownState: Future[RateState] = safeRateLimitCheck
  
  /*
   * get rate limit status without counting as part of quota
   */
  private def safeRateLimitCheck: Future[RateState] = 
    performApiCall(Http("https://api.github.com/rate_limit")) map { response =>
      if (!response.isSuccess) throw new Exception(s"github api bad or unexpected response: \n$response")
      new RateState(response)
  }
  
  private case class RateState(response: HttpResponse[String]) {
    
    val windowLimit     = response.headers("X-RateLimit-Limit").head.toInt
    val windowRemaining = response.headers("X-RateLimit-Remaining").head.toInt
    val windowEnd       = response.headers("X-RateLimit-Reset").head 
    
    /*
     * Use this function to always reserve some api quota, so that the api can always be 
     * manually examined outside the run of the application
     */
    def windowQuotaReserveLeft = (0.1 * windowLimit) > windowRemaining   
  }
  
  def nonBlockingHttp(apiCall: HttpRequest) = maybeApiCall(apiCall)
  
  private def maybeApiCall(apiCall: HttpRequest) = {
    lastKnownState.flatMap(_.windowQuotaReserveLeft match {
      case true  => performApiCall(apiCall)
      case false => 
        Future.failed[HttpResponse[String]](new Exception("stopping to avoid exhausting rate limit"))
    })
  } 
  
  private def performApiCall(apiCall: HttpRequest): Future[HttpResponse[String]] = {
    val response = Future { apiCall.asString }
    response.onComplete { 
      case Failure(f) => throw new Exception(s"failed completing github api call: \n$f") 
      case Success(response) => lastKnownState = Future.successful(RateState(response)) 
    }
    response
  }
}
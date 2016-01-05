package org.canve.githubCruncher
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scalaj.http._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.util.{Try, Success, Failure}
import scala.annotation.tailrec
import RateLimitedApiCaller._
          
trait GithubCrawler {  

  /*
   * get a relevant scala projects list from github api
   */
  def getProjectsList: Future[List[JsValue]] = {
    
    lazy val initialApiCall: HttpRequest = 
      Http("https://api.github.com/search/repositories")
      .param("q", "language:scala")
      .param("sort", "forks")

    var result: List[JsValue] = List() 
      
    def impl(apiCall:HttpRequest = initialApiCall): Future[List[JsValue]] = {
      
      nonBlockingHttp(apiCall) flatMap { response =>

        //case Failure(t) => throw new Exception(s"""github api call failed - have we been rate limited? original exception follows:\n$t""") 
        
          if (!response.isSuccess) 
            throw new Exception(s"github api bad response: \n$response")
  
          val asJson: JsValue = Json.parse(response.body) //println(Json.prettyPrint(asJson))
          val headers = response.headers
          val linkHeaders = parseGithubLinkHeader(headers("Link"))
          println(linkHeaders)

          val projects = (asJson \ "items")
            .as[JsArray]
            .as[List[JsValue]]
          
          result ++ projects 
          
          if (linkHeaders("next") != linkHeaders("last")) Future { result } 
          else impl(Http(linkHeaders("next"))) 
        }
      }
       
    impl() 
  }
  
  /*
   *  parses the link header field (http://tools.ietf.org/html/rfc5988) returned by Github's api 
   *  (c.f. https://developer.github.com/guides/traversing-with-pagination/)
   */
  private def parseGithubLinkHeader(linkHeader: IndexedSeq[String]): Map[String, String] = {
    assert(linkHeader.size == 1)
    linkHeader.head.split(',').map { linkEntry =>
      val entryPart = linkEntry.split(';')
      assert(entryPart.size == 2)
      val rel = entryPart(1).replace(" rel=\"", "").replace("\"", "")
      val url = entryPart(0).replace("<", "").replace(">", "")
      (rel, url)
    }.toMap
  }
}
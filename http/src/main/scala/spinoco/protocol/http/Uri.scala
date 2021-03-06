package spinoco.protocol.http

import java.net.{URLDecoder, URLEncoder}

import scodec.{Attempt, Codec}
import codec.helper._
import scodec.bits.{BitVector, ByteVector}
import spinoco.protocol.common.util._
import spinoco.protocol.common.codec._

/**
  * Internet Uri, as defined in http://tools.ietf.org/html/rfc3986
  * All values are decoded (no % escaping)
  */
sealed case class Uri(
  scheme: HttpScheme.Value
  , host: HostPort
  , path: Uri.Path
  , query: Uri.Query
) { self =>
  /** replaces query with one specified **/
  def withQuery(query: Uri.Query): Uri =
    self.copy(query = query)

  /** appends supplied param to uri **/
  def withParam(k: String, v: String): Uri =
    self.copy(query = Uri.Query(self.query.params :+ (k -> v)))

}


object Uri {

  def http(host: String, path: String): Uri =
    Uri(HttpScheme.HTTP, HostPort(host, None), Uri.Path.fromUtf8String(path), Query.empty)

  def http(host: String, port: Int, path: String): Uri =
    Uri(HttpScheme.HTTP, HostPort(host, Some(port)), Uri.Path.fromUtf8String(path), Query.empty)

  def https(host: String, path: String): Uri =
    Uri(HttpScheme.HTTPS, HostPort(host, None), Uri.Path.fromUtf8String(path), Query.empty)

  def https(host: String, port: Int, path: String): Uri =
    Uri(HttpScheme.HTTPS, HostPort(host, Some(port)), Uri.Path.fromUtf8String(path), Query.empty)

  def ws(host: String, path: String): Uri =
    Uri(HttpScheme.WS, HostPort(host, None), Uri.Path.fromUtf8String(path), Query.empty)

  def ws(host: String, port: Int, path: String): Uri =
    Uri(HttpScheme.WS, HostPort(host, Some(port)), Uri.Path.fromUtf8String(path), Query.empty)

  def wss(host: String, path: String): Uri =
    Uri(HttpScheme.WSS, HostPort(host, None), Uri.Path.fromUtf8String(path), Query.empty)

  def wss(host: String, port: Int, path: String): Uri =
    Uri(HttpScheme.WSS, HostPort(host, Some(port)), Uri.Path.fromUtf8String(path), Query.empty)

  /** parse supplied string and receive Uri, if supplied string is valid **/
  def parse(uriString: String): Attempt[Uri] =
    codec.decodeValue(BitVector.view(uriString.getBytes))

  val pathQueryCodec:Codec[(Uri.Path, Uri.Query)] = {
    parametrized(ByteVector('?'), Path.codec, Query.codec).xmap(
      { case (p,q) => p -> q.getOrElse(Query.empty) }
      , { case (p,q) => p -> (if (q.params.isEmpty) None else Some(q)) }
    )
  }

  val codec: Codec[Uri] = {

    val hostPathQueryCodec:Codec[(HostPort, Uri.Path, Uri.Query)] = {
      (bytesUntil(_ != '/').codedAs(HostPort.codec) ~ pathQueryCodec).xmap(
        { case (hp, (path, query)) => (hp, path, query) }
        , { case (hp, path, query)  =>  (hp, (path, query)) }
      )
    }

    tuple[HttpScheme.Value,(HostPort, Uri.Path, Uri.Query)](`://`, HttpScheme.codec, hostPathQueryCodec)
    .xmap(
      { case (scheme, (host, path, query)) => Uri(scheme, host, path, query) }
      , uri => (uri.scheme, (uri.host, uri.path, uri.query))
    )
  }


  sealed case class Path(initialSlash: Boolean, trailingSlash:Boolean, segments: Seq[String]) { self =>

    def / (s: String) =
      self.copy(trailingSlash = false, segments = self.segments :+ s)

    def / =
      self.copy(trailingSlash = true)


    def stringify:String = {
      val sb = new StringBuilder()
      if (self.initialSlash) sb.append("/")
      sb.append(self.segments.map(s => URLEncoder.encode(s, "UTF-8")).mkString("/"))
      if (self.trailingSlash) sb.append("/")
      sb.toString()
    }

    //override def toString: String = s"Uri.Path($stringify)"
  }

  object Path {

    /** constructs relative path without initial slash (`/`) **/
    def relative(s: String) : Path =
      Path(initialSlash = false, trailingSlash = false, segments = Seq(s))

    /** constructs absolute path with initial slash (`/`) **/
    def absolute(s: String) : Path =
      Path(initialSlash = true, trailingSlash = false, segments = Seq(s))

    def  / (s: String) : Path = absolute(s)

    def fromUtf8String(path: String):Uri.Path = {
      val trimmed = path.trim
      val segments = trimmed.split("/").filter(_.nonEmpty).map(s => URLDecoder.decode(s, "UTF-8"))
      Path(
        initialSlash =  trimmed.startsWith("/")
        , segments = segments
        , trailingSlash = trimmed.endsWith("/") && segments.nonEmpty
      )
    }


    val codec : Codec[Uri.Path] = {
      trimmedUtf8String.xmap(fromUtf8String, _.stringify)
    }

    val Root: Path = Path(initialSlash = true, segments = Nil, trailingSlash = false)

  }


  sealed case class Query(params: List[(String, String)]) { self =>

    def :+ (pair: (String, String)) : Query = self.copy(self.params :+ pair)


  }

  object Query {

    val empty = Query(List.empty)

    val codec:Codec[Query] = {
      val param: Codec[(String, String)] = {
        tuple(_equal, utf8String, utf8String).exmap(
          { case (k,v) => attempt { URLDecoder.decode(v.trim, "UTF-8") }.map { k.trim -> _ } }
          , { case (k,v) => attempt { URLEncoder.encode(v, "UTF-8") }.map { k.trim -> _ } }
        )
      }

      delimitedBy(amp,amp, param).xmap(Query(_), _.params)
    }



  }



}
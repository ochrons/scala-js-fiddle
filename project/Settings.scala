/**
  * Application settings. Configure the build for your application here.
  * You normally don't have to touch the actual build definition after this.
  */
object Settings {
  /** Options for the scala compiler */
  val scalacOptions = Seq(
    "-Xlint",
    "-unchecked",
    "-deprecation",
    "-feature"
  )

  /** Declare global dependency versions here to avoid mismatches in multi part dependencies */
  object versions {
    val fiddle = "1.0.6"
    val scalatest = "3.0.1"
    val macroParadise = "2.1.0"
    val akka = "2.4.16"
    val akkaHttp = "10.0.3"
    val upickle = "0.4.4"
    val ace = "1.2.3"
    val dom = "0.9.1"
    val scalatags = "0.6.2"
    val async = "0.9.6"
    val coursier = "1.0.0-M15-1"
  }

}

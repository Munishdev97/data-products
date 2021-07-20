package org.sunbird.analytics.exhaust.uci

import org.apache.spark.sql.functions.{col, when}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.ekstep.analytics.framework.conf.AppConf
import org.ekstep.analytics.framework.util.JSONUtils
import org.ekstep.analytics.framework.{FrameworkContext, JobConfig}
import org.sunbird.analytics.util.AESWrapper

import java.util.Properties
import scala.collection.immutable.List

object UCIPrivateExhaustJob extends optional.Application with BaseUCIExhaustJob {

  val connectionProps: Properties = getUCIPostgresConnectionProps(
    AppConf.getConfig("uci.fushionauth.postgres.user"),
    AppConf.getConfig("uci.fushionauth.postgres.pass")
  )
  val fusionAuthURL: String = AppConf.getConfig("uci.fushionauth.postgres.url") + s"${AppConf.getConfig("uci.fushionauth.postgres.db")}"
  val userTable: String = AppConf.getConfig("uci.postgres.table.user")
  val identityTable: String = AppConf.getConfig("uci.postgres.table.identities")
  val userRegistrationTable: String = AppConf.getConfig("uci.postgres.table.user_registration")

  val isConsentToShare = true // Default set to True

  private val columnsOrder = List("Conversation ID", "Conversation Name", "Device ID")
  private val columnMapping = Map("applications_id" -> "Conversation ID", "name" -> "Conversation Name", "device_id" -> "Device ID")

  /** START - Overridable Methods */
  override def jobId(): String = "uci-private-exhaust"

  override def jobName(): String = "UCIPrivateExhaust"

  override def getReportPath(): String = "uci-private-exhaust/"

  override def getReportKey(): String = "userinfo"

  override def getClassName(): String = "org.sunbird.analytics.exhaust.collection.UCIPrivateExhaustJob"

  override def process(conversationId: String, telemetryDF: DataFrame, conversationDF: DataFrame)(implicit spark: SparkSession, fc: FrameworkContext, config: JobConfig): DataFrame = {
    print("conversationId" + conversationId)
    val userRegistrationDF = loadUserRegistrationTable(conversationId)
    print("userRegistrationDF" + userRegistrationDF.show(false))
    val userDF = loadUserTable()
    val identitiesDF = loadIdentitiesTable()
    val decrypt = spark.udf.register("decrypt", decryptFn)
    val finalDF = conversationDF
      .join(userRegistrationDF, userRegistrationDF.col("applications_id") === conversationDF.col("id"), "inner")
      .join(userDF, Seq("device_id"), "inner")
      .join(identitiesDF, Seq("device_id"), "inner")
      // Decrypt the username column to get the mobile num based on the consent value
      .withColumn("device_id", when(col("consent") === true, decrypt(col("username"))).otherwise(col("device_id")))
      .select("applications_id", "name", "device_id")
    organizeDF(finalDF, columnMapping, columnsOrder)
  }

  /**
   *
   * Fetch the user Registration table data for a specific conversation ID
   */
  def loadUserRegistrationTable(conversationId: String)(implicit spark: SparkSession, fc: FrameworkContext): DataFrame = {
    fetchData(fusionAuthURL, connectionProps, userRegistrationTable).select("id", "applications_id")
      .filter(col("applications_id") === conversationId)
      .withColumnRenamed("id", "device_id")
  }

  /**
   * Fetch the user table data to get the consent information
   */
  def loadUserTable()(implicit spark: SparkSession, fc: FrameworkContext): DataFrame = {
    val consentValue = spark.udf.register("consent", getConsentValueFn)
    fetchData(fusionAuthURL, connectionProps, userTable).select("id", "data")
      .withColumnRenamed("id", "device_id")
      .withColumn("consent", consentValue(col("data")))
  }

  /**
   * Fetch the user identities table data
   * to get the mobile num by decrypting the username column based on consent
   */
  def loadIdentitiesTable()(implicit spark: SparkSession, fc: FrameworkContext): DataFrame = {
    fetchData(fusionAuthURL, connectionProps, identityTable).select("users_id", "username")
      .withColumnRenamed("users_id", "device_id")
  }

  def getConsentValueFn: String => Boolean = (device_data: String) => {
    val device = JSONUtils.deserialize[Map[String, AnyRef]](device_data)
    device.getOrElse("device", Map()).asInstanceOf[Map[String, AnyRef]].getOrElse("consent", isConsentToShare).asInstanceOf[Boolean]
  }

  def decryptFn: String => String = (encryptedValue: String) => {
    ""
    //AESWrapper.decrypt(encryptedValue, Some(AppConf.getConfig("uci_encryption_secret")))
  }

}

package durable.engine

import durable.NonRecoverableException

/**
 * Source of configuration for durable workflows.
 *
 * Provides access to external configuration (database connections, API keys, etc.)
 * that can be recreated on each workflow start/resume.
 */
trait ConfigSource:
  /**
   * Get raw configuration string for a section.
   *
   * @param section Section name (e.g., "database", "api.client")
   * @return Some(config) if found, None otherwise
   */
  def getRaw(section: String): Option[String]

object ConfigSource extends ConfigSourcePlatform:
  /** Empty config source - returns None for all sections */
  val empty: ConfigSource = _ => None

  /** Create config source from a map */
  def fromMap(entries: Map[String, String]): ConfigSource =
    section => entries.get(section)

/**
 * Thrown when a required config section is not found.
 * Extends NonRecoverableException to prevent retries.
 */
case class ConfigNotFoundException(section: String)
  extends RuntimeException(s"Config section not found: $section")
  with NonRecoverableException

/**
 * Thrown when config parsing fails.
 * Extends NonRecoverableException to prevent retries.
 */
case class ConfigParseException(section: String, cause: Throwable)
  extends RuntimeException(s"Failed to parse config section: $section", cause)
  with NonRecoverableException

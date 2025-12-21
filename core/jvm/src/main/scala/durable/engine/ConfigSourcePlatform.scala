package durable.engine

import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets

/**
 * JVM-specific ConfigSource factories.
 */
trait ConfigSourcePlatform:

  /**
   * Create a config source that reads from files in a directory.
   *
   * When getRaw(section) is called, reads the file at ${dirname}/${section}.${ext}
   *
   * @param dirname Directory containing configuration files
   * @param ext File extension (without dot), e.g., "json", "conf"
   * @return ConfigSource that reads from files
   *
   * Example:
   * {{{
   *   val config = ConfigSource.fromDirectory("/etc/myapp/config", "json")
   *   config.getRaw("database")  // reads /etc/myapp/config/database.json
   * }}}
   */
  def fromDirectory(dirname: String, ext: String): ConfigSource =
    fromDirectory(Paths.get(dirname), ext)

  /**
   * Create a config source that reads from files in a directory (Path version).
   */
  def fromDirectory(dir: Path, ext: String): ConfigSource =
    section =>
      val filePath = dir.resolve(s"$section.$ext")
      if Files.exists(filePath) && Files.isRegularFile(filePath) then
        Some(Files.readString(filePath, StandardCharsets.UTF_8))
      else
        None

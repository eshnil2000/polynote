package polynote.server.repository

import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.{FileAlreadyExistsException, Path, Paths}

import cats.implicits._
import polynote.kernel.environment.Config
import polynote.kernel.{BaseEnv, GlobalEnv}
import polynote.messages._
import polynote.server.repository.format.NotebookFormat
import polynote.server.repository.fs.{LocalFilesystem, NotebookFilesystem}
import zio.interop.catz._
import zio.{RIO, ZIO}

trait NotebookRepository {

  /**
    * @return Whether a notebook exists at the specified path
    */
  def notebookExists(path: String): RIO[BaseEnv with GlobalEnv, Boolean]

  /**
    * @return The location of the notebook (must be absolute)
    */
  def notebookURI(path: String): RIO[BaseEnv with GlobalEnv, Option[URI]]

  /**
    * @return The notebook at the specified path
    */
  def loadNotebook(path: String): RIO[BaseEnv with GlobalEnv, Notebook]

  /**
    * Save the given notebook to the specified path
    */
  def saveNotebook(nb: Notebook): RIO[BaseEnv with GlobalEnv, Unit]

  /**
    * @return A list of notebook paths that exist in this repository
    */
  def listNotebooks(): RIO[BaseEnv with GlobalEnv, List[String]]

  /**
    * Create a notebook at the given path, optionally creating it from the given content string.
    *
    * TODO: Server no longer imports. Need to implement this on the client side.
    */
  def createNotebook(path: String, maybeContent: Option[String]): RIO[BaseEnv with GlobalEnv, String]

  def copyNotebook(path: String, newPath: String, deletePrevious: Boolean): RIO[BaseEnv with GlobalEnv, String]

  def deleteNotebook(path: String): RIO[BaseEnv with GlobalEnv, Unit]

  /**
    * Initialize the storage for this repository (i.e. create directory if it doesn't exist)
    */
  def initStorage(): RIO[BaseEnv with GlobalEnv, Unit]
}

/**
  * Notebook Repository that delegates to child Repositories that correspond to Storage Mounts [[polynote.config.Mount]].
  *
  * @param root:  The repository for this node in the tree
  * @param repos: Map of mount points -> repositories for children of this node
  */
class TreeRepository (
  root: NotebookRepository,
  repos: Map[String, NotebookRepository]
) extends NotebookRepository {

  /**
    * Helper for picking the proper Repository to use for the given notebook path.
    *
    * @param notebookPath: The path to the notebook
    * @param f:            Function for `(NotebookRepository, relativePath: String, maybeBasePath: Option[String) => RIO[Env, T]`.
    *                      The presence of `maybeBasePath` reflects whether the path has been relativized (in case callers need to de-relativize any results)
    */
  private[repository] def delegate[T](notebookPath: String)(f: (NotebookRepository, String, Option[String]) => RIO[BaseEnv with GlobalEnv, T]): RIO[BaseEnv with GlobalEnv, T] = {
    val (originalPath, basePath) = extractPath(Paths.get(notebookPath))

    val (repoForPath, relativePath, maybeBasePath) = basePath.flatMap(base => repos.get(base.toString).map(_ -> base)) match {
      case Some((repo, base)) =>
          (repo, base.relativize(originalPath).toString, Option(base.toString))
      case None =>
        (root, originalPath.toString, None)
    }

    f(repoForPath, relativePath, maybeBasePath)
  }

  /**
    * Relativizes the path and extracts the base path (the top-most path member in this path)
    *
    * @return the path, relativized if necessary, and the basePath
    */
  private def extractPath(path: Path): (Path, Option[Path]) = {
    val forceRelativePath = path.subpath(0, path.getNameCount)  // root doesn't count with subpaths, so this trick forces the path to be relative

    (forceRelativePath, Option(forceRelativePath.getParent).map(_.getName(0)))
  }

  override def notebookExists(originalPath: String): RIO[BaseEnv with GlobalEnv, Boolean] = delegate(originalPath) {
    (repo, relativePath, _) => repo.notebookExists(relativePath)
  }

  // Because notebookURI is absolute path we don't need to modify it here
  override def notebookURI(originalPath: String): RIO[BaseEnv with GlobalEnv, Option[URI]] = delegate(originalPath) {
    (repo, relativePath, _) =>
      repo.notebookURI(relativePath)
  }

  override def loadNotebook(originalPath: String): RIO[BaseEnv with GlobalEnv, Notebook] = delegate(originalPath) {
    (repo, relativePath, base) =>
     for {
       nb <- repo.loadNotebook(relativePath)
     } yield base.map(b => nb.copy(path = Paths.get(b, nb.path).toString)).getOrElse(nb)
  }

  override def saveNotebook(nb: Notebook): RIO[BaseEnv with GlobalEnv, Unit] = delegate(nb.path) {
    (repo, relativePath, _) => repo.saveNotebook(nb.copy(path=relativePath))
  }

  override def listNotebooks(): RIO[BaseEnv with GlobalEnv, List[String]] = {
    for {
      rootNBs <- root.listNotebooks()
      mountNbs <- repos.map {
        case (base, repo) => repo.listNotebooks().map(_.map(nbPath => Paths.get(base, nbPath).toString))
      }.toList.sequence
    } yield rootNBs ++ mountNbs.flatten
  }

  override def createNotebook(originalPath: String, maybeContent: Option[String]): RIO[BaseEnv with GlobalEnv, String] = delegate(originalPath) {
    (repo, relativePath, base) =>
        for {
          nbPath <- repo.createNotebook(relativePath, maybeContent)
        } yield base.map(b => Paths.get(b, nbPath).toString).getOrElse(nbPath)
  }

  override def copyNotebook(src: String, dest: String, deletePrevious: Boolean): RIO[BaseEnv with GlobalEnv, String] = {
    val (srcPath, srcBase) = extractPath(Paths.get(src))

    val (destPath, destBase) = extractPath(Paths.get(dest))

    if (srcBase == destBase) {
      for {
        (copied, base) <- delegate(srcPath.toString) {
          (repo, repoRelativePathStr, base) =>
            val relativeDest = base.fold(destPath.toString)(repoBase => Paths.get(repoBase).relativize(destPath).toString)
            repo.copyNotebook(repoRelativePathStr, relativeDest, deletePrevious).map(_ -> base)
        }
      } yield base.map(b => Paths.get(b, copied).toString).getOrElse(copied)
    } else {
      // If the two paths are on different filesystems, we can't rely on an atomic move or copy, so we need to fall back
      // to handling the creation and deletion ourselves.
      for {
        srcNb                <- delegate(src)((repo, repoRelativePathStr, base) => repo.loadNotebook(repoRelativePathStr))
        (destRepoBase, dest) <- delegate(dest)((repo, repoRelativePathStr, base) => repo.saveNotebook(srcNb.copy(path=repoRelativePathStr)).map(_ => base -> repoRelativePathStr))
        _                    <- if (deletePrevious) delegate(src)((repo, repoRelativePathStr, _) => repo.deleteNotebook(repoRelativePathStr)) else ZIO.unit
      } yield destRepoBase.map(base => Paths.get(base, dest).toString).getOrElse(dest)
    }
  }

  override def deleteNotebook(originalPath: String): RIO[BaseEnv with GlobalEnv, Unit] = delegate(originalPath) {
    (repo, relativePath, _) => repo.deleteNotebook(relativePath)
  }

  override def initStorage(): RIO[BaseEnv with GlobalEnv, Unit] = for {
    _ <- root.initStorage()
    _ <- repos.values.map(_.initStorage()).toList.sequence
  } yield ()
}

class FileBasedRepository(
  val path: Path,
  val chunkSize: Int = 8192,
  val defaultExtension: String = "ipynb",
  val fs: NotebookFilesystem = new LocalFilesystem() // TODO: once we support other FS (like S3) we'll want this to be configurable
) extends NotebookRepository {
  protected def pathOf(relativePath: String): Path = path.resolve(relativePath)

  override def loadNotebook(path: String): RIO[BaseEnv with GlobalEnv, Notebook] = for {
    fmt     <- NotebookFormat.getFormat(pathOf(path))
    content <- fs.readPathAsString(pathOf(path))
    (noExtPath, _) = extractExtension(path)
    nb      <- fmt.decodeNotebook(noExtPath, content)
  } yield nb

  override def saveNotebook(nb: Notebook): RIO[BaseEnv with GlobalEnv, Unit] = for {
    fmt       <- NotebookFormat.getFormat(Paths.get(nb.path))
    rawString <- fmt.encodeNotebook(NotebookContent(nb.cells, nb.config))
    _         <- fs.writeStringToPath(pathOf(nb.path), rawString)
  } yield ()

  override def listNotebooks(): RIO[BaseEnv with GlobalEnv, List[String]] = {
    for {
      files <- fs.list(path)
      isSupported <- NotebookFormat.isSupported
    } yield files.filter(isSupported).map { relativePath =>
      path.relativize(relativePath).toString
    }
  }

  override def notebookExists(path: String): RIO[BaseEnv with GlobalEnv, Boolean] = fs.exists(pathOf(path))

  override def notebookURI(path: String): RIO[BaseEnv with GlobalEnv, Option[URI]] = {
    val repoPath = this.path.resolve(path)
    notebookExists(path).map {
      exists =>
        if (exists) {
          Option(repoPath.toAbsolutePath.toUri)
        } else {
          None
        }
    }
  }

  val EndsWithNum = """^(.*?)(\d+)$""".r

  def findUniqueName(path: String): RIO[BaseEnv with GlobalEnv, String] = {
    val (noExtPath, ext) = extractExtension(path)
    notebookExists(path).flatMap {
      case true =>
        noExtPath match {
          case EndsWithNum(base, num) =>
            findUniqueName(s"$base${num.toInt + 1}.$ext")
          case _ =>
            findUniqueName(s"${noExtPath}2.$ext") // start at two because the first one is implicitly #1? Or is that weird?
        }
      case false =>
        ZIO.succeed(path)
    }
  }

  def emptyNotebook(path: String, title: String): RIO[BaseEnv with GlobalEnv, Notebook] = Config.access.map {
    config =>
      Notebook(
        path,
        ShortList.of(NotebookCell(0, "text", s"# $title\n\nThis is a text cell. Start editing!")),
        Some(NotebookConfig.fromPolynoteConfig(config)))
  }

  private def extractExtension(path: String) = {
    path.lastIndexOf('.') match {
      case -1 => (path, defaultExtension)  // Adds the default extension if none is found.
      case idx =>
        (path.substring(0, idx), path.substring(idx + 1))
    }
  }

  override def createNotebook(relativePath: String, maybeContent: Option[String] = None): RIO[BaseEnv with GlobalEnv, String] = {
    val (noExtPath, ext) = extractExtension(relativePath.replaceFirst("""^/+""", ""))
    val path = s"$noExtPath.$ext"

    for {
      _       <- fs.validate(Paths.get(relativePath))
      fmt     <- NotebookFormat.getFormat(Paths.get(path))
      nb      <- maybeContent.map(content => fmt.decodeNotebook(noExtPath, content)).getOrElse {
        val defaultTitle = noExtPath.split('/').last.replaceAll("[\\s\\-_]+", " ").trim()
        emptyNotebook(path, defaultTitle)
      }
      name    <- findUniqueName(nb.path)
      _       <- saveNotebook(nb.copy(path = name))
    } yield name
  }

  override def copyNotebook(oldPath: String, newPath: String, deletePrevious: Boolean): RIO[BaseEnv with GlobalEnv, String] = {
    val ext = s".$defaultExtension"
    val withExt = newPath.replaceFirst("""^/+""", "").stripSuffix(ext) + ext

    fs.validate(Paths.get(withExt)) *> (notebookExists(oldPath), notebookExists(withExt)).mapN(_ -> _).flatMap {
      case (false, _)    => ZIO.fail(new FileNotFoundException(s"File $oldPath doesn't exist"))
      case (_, true)     => ZIO.fail(new FileAlreadyExistsException(s"File $withExt already exists"))
      case (true, false) =>
        val absOldPath = pathOf(oldPath)
        val absNewPath = pathOf(withExt)

        fs.copy(absOldPath, absNewPath, deletePrevious).as(withExt)
    }
  }

  // TODO: should probably have a "trash" or something instead – a way of recovering a file from accidental deletion?
  override def deleteNotebook(path: String): RIO[BaseEnv with GlobalEnv, Unit] = fs.delete(pathOf(path))

  override def initStorage(): RIO[BaseEnv with GlobalEnv, Unit] = fs.init(path)
}

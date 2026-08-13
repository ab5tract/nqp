package org.raku.nqp.runtime

import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.NotLinkException

object IOExceptionMessages {
    @JvmStatic
    fun message(e: Exception): String = when (e) {
        is FileNotFoundException,
        is NoSuchFileException -> "File " + e.message + " not found"
        is AccessDeniedException -> "Access to " + e.message + " is denied"
        is DirectoryNotEmptyException -> "Directory " + e.message + " not empty"
        is FileAlreadyExistsException -> "File " + e.message + " already exists"
        is NotDirectoryException -> "File " + e.message + " is not a directory"
        is NotLinkException -> "File " + e.message + " is not a link"
        else -> e.javaClass.simpleName + ": " + e.message
    }
}

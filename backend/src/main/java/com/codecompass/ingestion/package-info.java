/**
 * Ingestion package – Stage 2
 *
 * Responsible for:
 *  - Accepting a Git repository URL from the API layer
 *  - Cloning the repo to a local temp directory via JGit
 *  - Walking the cloned directory tree and collecting all .java files
 *
 * Key classes (added in Stage 2):
 *  - RepoIngestionService   : orchestrates clone → walk → parse pipeline
 *  - JavaFileWalker         : recursive .java file collector
 */
@NonNullApi
package com.codecompass.ingestion;

import org.springframework.lang.NonNullApi;

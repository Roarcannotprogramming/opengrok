/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2008, 2025, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2017, 2020, Chris Fraire <cfraire@me.com>.
 */
package org.opengrok.indexer.index;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.DateTools;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockFactory;
import org.apache.lucene.store.NativeFSLockFactory;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.store.SimpleFSLockFactory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;
import org.opengrok.indexer.analysis.AbstractAnalyzer;
import org.opengrok.indexer.analysis.AnalyzerFactory;
import org.opengrok.indexer.analysis.AnalyzerGuru;
import org.opengrok.indexer.analysis.Ctags;
import org.opengrok.indexer.analysis.Definitions;
import org.opengrok.indexer.analysis.NullableNumLinesLOC;
import org.opengrok.indexer.analysis.NumLinesLOC;
import org.opengrok.indexer.configuration.PathAccepter;
import org.opengrok.indexer.configuration.Project;
import org.opengrok.indexer.configuration.RuntimeEnvironment;
import org.opengrok.indexer.configuration.SuperIndexSearcher;
import org.opengrok.indexer.history.CacheException;
import org.opengrok.indexer.history.FileCollector;
import org.opengrok.indexer.history.HistoryGuru;
import org.opengrok.indexer.history.Repository;
import org.opengrok.indexer.history.RepositoryInfo;
import org.opengrok.indexer.history.RepositoryWithHistoryTraversal;
import org.opengrok.indexer.logger.LoggerFactory;
import org.opengrok.indexer.search.QueryBuilder;
import org.opengrok.indexer.util.ForbiddenSymlinkException;
import org.opengrok.indexer.util.IOUtils;
import org.opengrok.indexer.util.ObjectPool;
import org.opengrok.indexer.util.Progress;
import org.opengrok.indexer.util.Statistics;
import org.opengrok.indexer.util.TandemPath;
import org.opengrok.indexer.web.Util;

/**
 * This class is used to create / update the index databases. Currently, we use
 * one index database per project.
 *
 * @author Trond Norbye
 * @author Lubos Kosco , update for lucene 4.x , 5.x
 */
public class IndexDatabase {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexDatabase.class);

    @VisibleForTesting
    static final Comparator<File> FILENAME_COMPARATOR = Comparator.comparing(File::getName);

    @VisibleForTesting
    static final Comparator<Path> FILEPATH_COMPARATOR = (p1, p2) -> {
        int nameCount = Math.min(p1.getNameCount(), p2.getNameCount());
        int i;
        for (i = 0; i < nameCount; i++) {
            var c1 = p1.getName(i).toString();
            var c2 = p2.getName(i).toString();
            if (c1.equals(c2)) {
                continue;
            }
            return c1.compareTo(c2);
        }

        return Integer.compare(p1.getNameCount(), p2.getNameCount());
    };

    private static final Set<String> CHECK_FIELDS;

    private static final Set<String> REVERT_COUNTS_FIELDS;

    private static final Set<String> LIVE_CHECK_FIELDS;

    private static final Object INSTANCE_LOCK = new Object();

    /**
     * Key is canonical path; Value is the first accepted, absolute path. Map
     * is ordered by canonical length (ASC) and then canonical value (ASC).
     * The map is accessed by a single-thread running indexDown().
     */
    private final Map<String, IndexedSymlink> indexedSymlinks = new TreeMap<>(
            Comparator.comparingInt(String::length).thenComparing(o -> o));

    @Nullable
    private final Project project;
    private FSDirectory indexDirectory;
    private IndexReader reader;
    private IndexWriter writer;
    private IndexAnalysisSettings3 settings;
    private PendingFileCompleter completer;
    private NumLinesLOCAggregator countsAggregator;
    private TermsEnum uidIter;
    private PostingsEnum postsIter;
    private PathAccepter pathAccepter;
    private AnalyzerGuru analyzerGuru;
    private File xrefDir;
    private CopyOnWriteArrayList<IndexChangedListener> listeners;
    private File dirtyFile;
    private final Object lock = new Object();
    private boolean dirty;  // Whether the index was modified either by adding or removing a document.
    private boolean running;
    private boolean isCountingDeltas;
    private boolean isWithDirectoryCounts;
    private String directory;
    private LockFactory lockFactory;
    private final BytesRef emptyBR = new BytesRef("");
    private final Set<String> deletedUids = new HashSet<>();

    // Directory where we store indexes
    public static final String INDEX_DIR = "index";
    public static final String XREF_DIR = "xref";
    public static final String SUGGESTER_DIR = "suggester";

    private final IndexDownArgsFactory indexDownArgsFactory;

    private final IndexWriterConfigFactory indexWriterConfigFactory;

    /**
     * Create a new instance of the Index Database. Use this constructor if you
     * don't use any projects
     *
     * @throws java.io.IOException if an error occurs while creating directories
     */
    public IndexDatabase() throws IOException {
        this(null);
    }

    /**
     * Anyone using this constructor is supposed to never call {@link #update()}.
     * Do not use for anything besides testing.
     * @param uidIter uid iterator
     * @param writer index writer
     * @throws IOException on error
     */
    IndexDatabase(Project project, TermsEnum uidIter, IndexWriter writer) throws IOException {
        this(project, new IndexDownArgsFactory());
        this.uidIter = uidIter;
        this.writer = writer;
        this.completer = new PendingFileCompleter();
        initialize();
    }

    /**
     * Create a new instance of an Index Database for a given project.
     *
     * @param project the project to create the database for
     * @param factory {@link IndexDownArgsFactory} instance
     * @throws java.io.IOException if an error occurs while creating directories
     */
    IndexDatabase(Project project, IndexDownArgsFactory factory) throws IOException {
        indexDownArgsFactory = factory;
        this.project = project;
        lockFactory = NoLockFactory.INSTANCE;
        indexWriterConfigFactory = new IndexWriterConfigFactory();
        initialize();
    }

    /**
     * Create a new instance of an Index Database for a given project.
     *
     * @param project the project to create the database for
     * @param indexDownArgsFactory {@link IndexDownArgsFactory} instance
     * @param indexWriterConfigFactory {@link IndexWriterConfigFactory} instance
     * @throws java.io.IOException if an error occurs while creating directories
     */
    IndexDatabase(@NotNull Project project, IndexDownArgsFactory indexDownArgsFactory,
                         IndexWriterConfigFactory indexWriterConfigFactory) throws IOException {
        this.indexDownArgsFactory = indexDownArgsFactory;
        this.project = project;
        lockFactory = NoLockFactory.INSTANCE;
        this.indexWriterConfigFactory = indexWriterConfigFactory;
        initialize();
    }

    IndexDatabase(Project project) throws IOException {
        this(project, new IndexDownArgsFactory());
    }

    static {
        CHECK_FIELDS = new HashSet<>();
        CHECK_FIELDS.add(QueryBuilder.TYPE);

        REVERT_COUNTS_FIELDS = new HashSet<>();
        REVERT_COUNTS_FIELDS.add(QueryBuilder.D);
        REVERT_COUNTS_FIELDS.add(QueryBuilder.PATH);
        REVERT_COUNTS_FIELDS.add(QueryBuilder.NUML);
        REVERT_COUNTS_FIELDS.add(QueryBuilder.LOC);

        LIVE_CHECK_FIELDS = new HashSet<>();
        LIVE_CHECK_FIELDS.add(QueryBuilder.U);
        LIVE_CHECK_FIELDS.add(QueryBuilder.PATH);
    }

    public static void addIndexDatabaseForProject(@Nullable IndexDatabase db, Project project, List<IndexDatabase> dbs,
                                           Map<Repository, Optional<Exception>> historyCacheResults) throws IOException {

        Map<Repository, Optional<Exception>> projectReposWithException = historyCacheResults.entrySet().
                stream().
                filter(e -> e.getValue().isPresent()).
                filter(e -> project.equals(Project.getProject(e.getKey().getDirectoryNameRelative()))).
                collect(Collectors.toMap(Entry::getKey, Entry::getValue));

        if (projectReposWithException.isEmpty()) {
            dbs.add(db != null ? db : new IndexDatabase(project));
        } else {
            LOGGER.log(Level.SEVERE, "Failed to create history cache for some repositories of project {0}: {1}",
                    new Object[]{project, projectReposWithException});
        }
    }

    public static void addIndexDatabase(@Nullable IndexDatabase db, List<IndexDatabase> dbs,
                                        Map<Repository, Optional<Exception>> historyCacheResults) throws IOException {

        Map<Repository, Optional<Exception>> reposWithException = historyCacheResults.entrySet().stream().
                filter(e -> e.getValue().isPresent()).
                collect(Collectors.toMap(Entry::getKey, Entry::getValue));

        if (reposWithException.isEmpty()) {
            dbs.add(db != null ? db : new IndexDatabase());
        } else {
            LOGGER.log(Level.SEVERE, "Failed to create history cache for some repositories: {0}",
                    reposWithException);
        }
    }

    /**
     * Update the index database for all the projects.
     *
     * @param listener where to signal the changes to the database
     * @param historyCacheResults map of repository to optional exception
     * @throws IOException if an error occurs
     * @throws IndexerException if indexing failed for any reason
     */
    static void updateAll(IndexChangedListener listener,
                                    Map<Repository, Optional<Exception>> historyCacheResults)
            throws IOException, IndexerException {

        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        List<IndexDatabase> dbs = new ArrayList<>();

        if (env.isProjectsEnabled()) {
            for (Project project : env.getProjectList()) {
                addIndexDatabaseForProject(null, project, dbs, historyCacheResults);
            }
        } else {
            addIndexDatabase(null, dbs, historyCacheResults);
        }

        IndexerParallelizer parallelizer = RuntimeEnvironment.getInstance().getIndexerParallelizer();
        CountDownLatch latch = new CountDownLatch(dbs.size());
        IndexerException exception = new IndexerException();
        for (IndexDatabase d : dbs) {
            final IndexDatabase db = d;
            if (listener != null) {
                db.addIndexChangedListener(listener);
            }

            parallelizer.getFixedExecutor().submit(() -> {
                try {
                    db.update();
                } catch (Throwable e) {
                    exception.addSuppressed(e);
                    LOGGER.log(Level.SEVERE, String.format("Problem updating index database in directory '%s': ",
                            db.indexDirectory.getDirectory()), e);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            exception.addSuppressed(e);
        }

        if (exception.getSuppressed().length > 0) {
            throw exception;
        }
    }

    @SuppressWarnings("PMD.CollapsibleIfStatements")
    private void initialize() throws IOException {
        synchronized (INSTANCE_LOCK) {
            RuntimeEnvironment env = RuntimeEnvironment.getInstance();
            File indexDir = new File(env.getDataRootFile(), INDEX_DIR);
            if (project != null) {
                indexDir = new File(indexDir, project.getPath());
            }

            if (!indexDir.exists() && !indexDir.mkdirs()) {
                // to avoid race conditions, just recheck..
                if (!indexDir.exists()) {
                    throw new FileNotFoundException("Failed to create root directory [" + indexDir.getAbsolutePath() + "]");
                }
            }

            lockFactory = pickLockFactory(env);
            indexDirectory = FSDirectory.open(indexDir.toPath(), lockFactory);
            pathAccepter = env.getPathAccepter();
            analyzerGuru = new AnalyzerGuru();
            xrefDir = new File(env.getDataRootFile(), XREF_DIR);
            listeners = new CopyOnWriteArrayList<>();
            dirtyFile = new File(indexDir, "dirty");
            dirty = dirtyFile.exists();
            if (project == null) {
                directory = "";
            } else {
                directory = project.getPath();
            }

            if (dirty) {
                LOGGER.log(Level.WARNING, "Index in ''{0}'' is dirty, the last indexing was likely interrupted." +
                        " It might be worthwhile to reindex from scratch.", indexDir);
            }
        }
    }

    private void showFileCount(String dir, IndexDownArgs args) {
        if (RuntimeEnvironment.getInstance().isPrintProgress()) {
            LOGGER.log(Level.INFO, String.format("Need to process: %d files for %s", args.curCount, dir));
        }
    }

    private void markProjectIndexed(Project project) {
        RuntimeEnvironment env = RuntimeEnvironment.getInstance();

        // Successfully indexed the project. The message is sent even if
        // the project's isIndexed() is true because it triggers RepositoryInfo
        // refresh.
        if (project == null) {
            return;
        }

        // Also need to store the correct value in configuration when indexer writes it to a file.
        project.setIndexed(true);

        if (env.getConfigURI() == null) {
            return;
        }

        // If this project is not known to the webapp yet, there is no point in setting its indexed property.
        Collection<String> webappProjects = IndexerUtil.getProjects(env.getConfigURI());
        if (!webappProjects.contains(project.getName())) {
            LOGGER.log(Level.FINEST, "Project {0} is not known to the webapp", project);
            return;
        }

        IndexerUtil.markProjectIndexed(env.getConfigURI(), project);
    }

    private static List<Repository> getRepositoriesForProject(Project project) {
        List<Repository> repositoryList = new ArrayList<>();

        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        List<RepositoryInfo> repositoryInfoList = env.getProjectRepositoriesMap().get(project);

        if (repositoryInfoList != null) {
            for (RepositoryInfo repositoryInfo : repositoryInfoList) {
                Repository repository = HistoryGuru.getInstance().getRepository(new File(repositoryInfo.getDirectoryName()));
                if (repository != null) {
                    repositoryList.add(repository);
                }
            }
        }

        return repositoryList;
    }

    /**
     * @return whether the repositories of given project are ready for history based reindex
     */
    private boolean isReadyForHistoryBasedReindex() {
        RuntimeEnvironment env = RuntimeEnvironment.getInstance();

        // So far the history based reindex does not work without projects.
        if (!env.hasProjects()) {
            LOGGER.log(Level.FINEST, "projects are disabled, will be indexed by directory traversal.");
            return false;
        }

        if (project == null) {
            LOGGER.log(Level.FINEST, "no project, will be indexed by directory traversal.");
            return false;
        }

        // History needs to be enabled for the history cache to work (see the comment below).
        if (!project.isHistoryEnabled()) {
            LOGGER.log(Level.FINEST,
                    "history is disabled for project {0}, will be indexed by directory traversal.",
                    project);
            return false;
        }

        // History cache is necessary to get the last indexed revision for given repository.
        if (!env.isHistoryCache()) {
            LOGGER.log(Level.FINEST,
                    "history cache is disabled for project {0}, will be indexed by directory traversal.",
                    project);
            return false;
        }

        // Per project tunable can override the global tunable, therefore env.isHistoryBasedReindex() is not checked.
        if (!project.isHistoryBasedReindex()) {
            LOGGER.log(Level.FINEST,
                    "history-based reindex is disabled for project {0}, will be indexed by directory traversal.",
                    project);
            return false;
        }

        /*
         * Check that the index is present for this project.
         * In case of the initial indexing, the traversal of all changesets would most likely be counterproductive,
         * assuming traversal of directory tree is cheaper than getting the files from SCM history
         * in such case.
         */
        try {
            if (getNumFiles() == 0) {
                LOGGER.log(Level.FINEST, "zero number of documents for project {0}, " +
                        "will be indexed by directory traversal.", project);
                return false;
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINEST, "failed to get number of documents for project {0}," +
                    "will be indexed by directory traversal.", project);
            return false;
        }

        // If there was no change to any of the repositories of the project, a FileCollector instance will be returned
        // however the list of files therein will be empty which is legitimate situation (no change of the project).
        // Only in a case where getFileCollector() returns null (hinting at something went wrong),
        // the file based traversal should be done.
        if (env.getFileCollector(project.getName()) == null) {
            LOGGER.log(Level.FINEST, "no file collector for project {0}, will be indexed by directory traversal.",
                    project);
            return false;
        }

        List<Repository> repositories = getRepositoriesForProject(project);
        // Projects without repositories have to be indexed using indexDown().
        if (repositories.isEmpty()) {
            LOGGER.log(Level.FINEST, "project {0} has no repositories, will be indexed by directory traversal.",
                    project);
            return false;
        }

        for (Repository repository : repositories) {
            if (!isReadyForHistoryBasedReindex(repository)) {
                return false;
            }
        }

        // Here it is assumed there are no files untracked by the repositories of this project.
        return true;
    }

    /**
     * @param repository Repository instance
     * @return true if the repository can be used for history based reindex
     */
    @VisibleForTesting
    boolean isReadyForHistoryBasedReindex(Repository repository) {
        if (!repository.isHistoryEnabled()) {
            LOGGER.log(Level.FINE, "history is disabled for {0}, " +
                    "the associated project {1} will be indexed using directory traversal",
                    new Object[]{repository, project});
            return false;
        }

        if (!repository.isHistoryBasedReindex()) {
            LOGGER.log(Level.FINE, "history based reindex is disabled for {0}, " +
                            "the associated project {1} will be indexed using directory traversal",
                    new Object[]{repository, project});
            return false;
        }

        if (!(repository instanceof RepositoryWithHistoryTraversal)) {
            LOGGER.log(Level.FINE, "project {0} has a repository {1} that does not support history traversal," +
                            "the project will be indexed using directory traversal.",
                    new Object[]{project, repository});
            return false;
        }

        return true;
    }

    /**
     * Update the content of this index database.
     *
     * @throws IOException if an error occurs
     * @throws IndexerException if the indexing was incomplete/failed
     */
    public void update() throws IOException, IndexerException {
        synchronized (lock) {
            if (running) {
                throw new IndexerException("Indexer already running!");
            }
            running = true;
        }

        RuntimeEnvironment env = RuntimeEnvironment.getInstance();

        reader = null;
        writer = null;
        settings = null;
        uidIter = null;
        postsIter = null;
        indexedSymlinks.clear();

        IOException finishingException = null;
        try {
            writer = new IndexWriter(indexDirectory, indexWriterConfigFactory.get());
            writer.commit(); // to make sure index exists on the disk
            completer = new PendingFileCompleter();

            String dir = this.directory;
            File sourceRoot;
            if ("".equals(dir)) {
                sourceRoot = env.getSourceRootFile();
            } else {
                sourceRoot = new File(env.getSourceRootFile(), dir);
            }

            dir = Util.fixPathIfWindows(dir);

            String startUid = Util.path2uid(dir, "");
            reader = DirectoryReader.open(indexDirectory); // open existing index
            setupDeletedUids();
            countsAggregator = new NumLinesLOCAggregator();
            settings = readAnalysisSettings();
            if (settings == null) {
                settings = new IndexAnalysisSettings3();
            }
            Terms terms = null;
            if (reader.numDocs() > 0) {
                terms = MultiTerms.getTerms(reader, QueryBuilder.U);

                NumLinesLOCAccessor countsAccessor = new NumLinesLOCAccessor();
                if (countsAccessor.hasStored(reader)) {
                    isWithDirectoryCounts = true;
                    isCountingDeltas = true;
                } else {
                    boolean foundCounts = countsAccessor.register(countsAggregator, reader);
                    isWithDirectoryCounts = false;
                    isCountingDeltas = foundCounts;
                    if (!isCountingDeltas) {
                        LOGGER.info("Forcing reindexing to fully compute directory counts");
                    }
                }
            } else {
                isWithDirectoryCounts = false;
                isCountingDeltas = false;
            }

            try {
                if (terms != null) {
                    uidIter = terms.iterator();
                    // The seekCeil() is pretty important because it makes uidIter.term() to become non-null.
                    // Various indexer methods rely on this when working with the uidIter iterator - rather
                    // than calling uidIter.next() first thing, they check uidIter.term().
                    TermsEnum.SeekStatus stat = uidIter.seekCeil(new BytesRef(startUid));
                    if (stat == TermsEnum.SeekStatus.END) {
                        uidIter = null;
                        LOGGER.log(Level.WARNING,
                            "Could not find a start term for {0}, empty u field?", startUid);
                    }
                }

                // The actual indexing happens in indexParallel(). Here we merely collect the files
                // that need to be indexed and the files that should be removed.
                IndexDownArgs args = indexDownArgsFactory.getIndexDownArgs();
                boolean usedHistory = getIndexDownArgs(dir, sourceRoot, args);

                // Traverse the trailing terms. This needs to be done before indexParallel() because
                // in some cases it can add items to the args parameter.
                processTrailingTerms(startUid, usedHistory, args);

                args.curCount = 0;
                Statistics elapsed = new Statistics();
                LOGGER.log(Level.INFO, "Starting indexing of directory ''{0}''", dir);
                indexParallel(dir, args);
                elapsed.report(LOGGER, String.format("Done indexing of directory '%s'", dir),
                        "indexer.db.directory.index");

                /*
                 * As a signifier that #Lines/LOC are comprehensively
                 * stored so that later calculation is in deltas mode, we
                 * need at least one D-document saved. For a repo with only
                 * non-code files, however, no true #Lines/LOC will have
                 * been saved. Subsequent re-indexing will do more work
                 * than necessary (until a source code file is placed). We
                 * can record zeroes for a fake file under the root to get
                 * a D-document even for this special repo situation.
                 *
                 * Metrics are aggregated for directories up to the root,
                 * so it suffices to put the fake directly under the root.
                 */
                if (!isWithDirectoryCounts) {
                    final String ROOT_FAKE_FILE = "/.OpenGrok_fake_file";
                    countsAggregator.register(new NumLinesLOC(ROOT_FAKE_FILE, 0, 0));
                }
                NumLinesLOCAccessor countsAccessor = new NumLinesLOCAccessor();
                countsAccessor.store(writer, reader, countsAggregator,
                        isWithDirectoryCounts && isCountingDeltas);

                markProjectIndexed(project);
            } finally {
                reader.close();
            }

            // The RuntimeException thrown from the block above can prevent the writing from completing.
            // This is deliberate.
            try {
                finishWriting();
            } catch (IOException e) {
                finishingException = e;
            }
        } catch (RuntimeException ex) {
            LOGGER.log(Level.SEVERE, "Failed with unexpected RuntimeException", ex);
            throw ex;
        } finally {
            completer = null;
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException e) {
                if (finishingException == null) {
                    finishingException = e;
                }
                LOGGER.log(Level.WARNING, "An error occurred while closing writer", e);
            } finally {
                writer = null;
                synchronized (lock) {
                    running = false;
                }
            }
        }

        if (finishingException != null) {
            throw finishingException;
        }

        if (isDirty()) {
            unsetDirty();
            env.setIndexTimestamp();
        }
    }

    /**
     * The traversal of the uid terms done in {@link #processFile(IndexDownArgs, File, String)}
     * and {@link #processFileHistoryBased(IndexDownArgs, File, String)} needs to skip over deleted documents
     * that are often found in multi-segment indexes. This method stores the uids of these documents
     * and is expected to be called before the traversal for the top level directory is started.
     * @throws IOException if the index cannot be read for some reason
     */
    private void setupDeletedUids() throws IOException {
        // This method might be called repeatedly from within the same IndexDatabase instance
        // for various directories so the map needs to be reset so that it does not contain unrelated uids.
        deletedUids.clear();

        Bits liveDocs = MultiBits.getLiveDocs(reader);  // Will return null if there are no deletions.
        if (liveDocs == null) {
            LOGGER.log(Level.FINEST, "no deletions found in {0}", reader);
            return;
        }

        Statistics stat = new Statistics();
        LOGGER.log(Level.FINEST, "traversing the documents in {0} to collect uids of deleted documents",
                indexDirectory);
        StoredFields storedFields = reader.storedFields();
        for (int i = 0; i < reader.maxDoc(); i++) {
            Document doc = storedFields.document(i, LIVE_CHECK_FIELDS);  // use limited-field version
            IndexableField field = doc.getField(QueryBuilder.U);
            if (!liveDocs.get(i)) {
                if (field != null) {
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        String uidString = field.stringValue();
                        LOGGER.log(Level.FINEST, "adding ''{0}'' ({2}) at {1} to deleted uid set",
                                new Object[]{Util.uid2url(uidString), Util.uid2date(uidString), i});
                    }
                    deletedUids.add(field.stringValue());
                }
            } else {
                if (field != null) {
                    String uidString = field.stringValue();
                    LOGGER.log(Level.FINEST, "live doc: ''{0}'' ({2}) at {1}",
                            new Object[]{Util.uid2url(uidString), Util.uid2date(uidString), i});
                }
            }
        }
        stat.report(LOGGER, Level.FINEST, String.format("found %s deleted documents in %s",
                deletedUids.size(), indexDirectory));
    }

    private void logIgnoredUid(String uid) {
        LOGGER.log(Level.FINEST, "ignoring deleted document for ''{0}'' at {1}",
                new Object[]{Util.uid2url(uid), Util.uid2date(uid)});
    }

    private void processTrailingTerms(String startUid, boolean usedHistory, IndexDownArgs args) throws IOException {
        while (uidIter != null && uidIter.term() != null
                && uidIter.term().utf8ToString().startsWith(startUid)) {

            if (deletedUids.contains(uidIter.term().utf8ToString())) {
                logIgnoredUid(uidIter.term().utf8ToString());
                BytesRef next = uidIter.next();
                if (next == null) {
                    uidIter = null;
                }
                continue;
            }

            if (usedHistory) {
                // Allow for forced reindex. For history based reindex the trailing terms
                // correspond to the files that have not changed. Such files might need to be re-indexed
                // if the index format changed.
                String termPath = Util.uid2url(uidIter.term().utf8ToString());
                File termFile = new File(RuntimeEnvironment.getInstance().getSourceRootFile(), termPath);
                boolean matchOK = (isWithDirectoryCounts || isCountingDeltas) &&
                        checkSettings(termFile, termPath);
                if (!matchOK) {
                    removeFile(false);

                    args.curCount++;
                    args.works.add(new IndexFileWork(termFile, termPath));
                }
            } else {
                // Remove data for the trailing terms that getIndexDownArgs()
                // did not traverse. These correspond to the files that have been
                // removed and have higher ordering than any present files.
                removeFile(true);
            }

            BytesRef next = uidIter.next();
            if (next == null) {
                uidIter = null;
            }
        }
    }

    /**
     * @param dir directory path
     * @param sourceRoot source root File object
     * @param args {@link IndexDownArgs} instance (output)
     * @return true if history was used to gather the {@code IndexDownArgs}
     * @throws IOException on error
     */
    @VisibleForTesting
    boolean getIndexDownArgs(String dir, File sourceRoot, IndexDownArgs args) throws IOException {
        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        boolean historyBased = isReadyForHistoryBasedReindex();

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.log(Level.INFO, String.format("Starting file collection using %s traversal for directory '%s'",
                    historyBased ? "history" : "file-system", dir));
        }
        Statistics elapsed = new Statistics();
        if (historyBased) {
            indexDownUsingHistory(env.getSourceRootFile(), args);
        } else {
            String logSuffix = project != null ? " for project " + project : String.format(" for directory '%s'", dir);
            try (Progress progress = new Progress(LOGGER, String.format("file collection%s", logSuffix))) {
                indexDown(sourceRoot, dir, args, progress);
            }
        }

        elapsed.report(LOGGER, String.format("Done file collection for directory '%s'", dir),
                "indexer.db.collection");

        showFileCount(dir, args);

        return historyBased;
    }

    /**
     * @param file file under source root
     * @return false if the document date is newer or equal to the last modified time stamp of the file, otherwise true
     */
    private static boolean isStrictlyNewerThanDocument(File file) {
        if (!file.exists()) {
            // Case of delete/renamed file.
            return true;
        }
        try {
            Document doc = IndexDatabase.getDocument(file);
            if (Objects.isNull(doc)) {
                LOGGER.log(Level.WARNING, "cannot get document for ''{0}''", file);
                return true;
            }
            IndexableField field = doc.getField(QueryBuilder.DATE);
            try {
                Date docDate = DateTools.stringToDate(field.stringValue());
                // Assumes millisecond precision.
                long lastModified = file.lastModified();
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINEST, String.format("checking date for '%s': %d %d",
                            file, lastModified, docDate.getTime()));
                }
                if (lastModified <= docDate.getTime()) {
                    return false;
                }
            } catch (java.text.ParseException e) {
                LOGGER.log(Level.WARNING, String.format("cannot convert date for '%s'", file), e);
                return true;
            }
        } catch (ParseException | IOException e) {
            LOGGER.log(Level.WARNING, String.format("cannot get document for '%s'", file), e);
        }

        return true;
    }

    /**
     * Executes the first, serial stage of indexing, by going through set of files assembled from history.
     * @param sourceRoot path to the source root (same as {@link RuntimeEnvironment#getSourceRootPath()})
     * @param args {@link IndexDownArgs} instance where the resulting files to be indexed will be stored
     * @throws IOException on error
     */
    @VisibleForTesting
    void indexDownUsingHistory(File sourceRoot, IndexDownArgs args) throws IOException {

        FileCollector fileCollector = RuntimeEnvironment.getInstance().getFileCollector(project.getName());

        try (Progress progress = new Progress(LOGGER, String.format("collecting files for %s", project),
                fileCollector.getFiles().size())) {
            List<Path> paths = fileCollector.getFiles().stream().
                    map(Path::of).
                    sorted(FILEPATH_COMPARATOR).
                    collect(Collectors.toList());
            LOGGER.log(Level.FINEST, "collected sorted files: {0}", paths);
            for (Path path : paths) {
                File file = new File(sourceRoot, path.toString());
                progress.increment();
                //
                // If the changes to the file were nullified across a sequence of changesets, the repository
                // might not have updated the file. The history collector is not that smart however,
                // so handle such situation here.
                //
                if (!isStrictlyNewerThanDocument(file)) {
                    LOGGER.log(Level.FINEST, "file ''{0}'' is not newer than its document, skipping",
                            new Object[]{file});
                    continue;
                }
                processFileHistoryBased(args, file, path.toString());
            }
        }
    }

    /**
     * Reduce segment counts of all index databases.
     *
     * @throws IOException if an error occurs
     */
    static void reduceSegmentCountAll() throws IOException {
        List<IndexDatabase> dbs = new ArrayList<>();
        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        IndexerParallelizer parallelizer = env.getIndexerParallelizer();
        if (env.hasProjects()) {
            for (Project project : env.getProjectList()) {
                dbs.add(new IndexDatabase(project));
            }
        } else {
            dbs.add(new IndexDatabase());
        }

        CountDownLatch latch = new CountDownLatch(dbs.size());
        for (IndexDatabase d : dbs) {
            final IndexDatabase db = d;
            parallelizer.getFixedExecutor().submit(() -> {
                try {
                    db.reduceSegmentCount();
                } catch (Throwable e) {
                    LOGGER.log(Level.SEVERE,
                        "Problem reducing segment count of Lucene index database: ", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            LOGGER.info("Waiting for the Lucene segment count reduction to finish");
            latch.await();
        } catch (InterruptedException exp) {
            LOGGER.log(Level.WARNING, "Received interrupt while waiting" +
                    " for index segment count reduction to finish", exp);
        }
    }

    /**
     * Reduce number of segments in the index database.
     * @throws IOException I/O exception
     */
    public void reduceSegmentCount() throws IOException {
        synchronized (lock) {
            if (running) {
                LOGGER.warning("Segment count reduction terminated... Someone else is running the operation!");
                return;
            }
            running = true;
        }

        IndexWriter wrt = null;
        IOException writerException = null;
        try {
            Statistics elapsed = new Statistics();
            String projectDetail = this.project != null ? " for project " + project.getName() : "";
            LOGGER.log(Level.INFO, "Reducing number of segments in the index{0}", projectDetail);
            Analyzer analyzer = new StandardAnalyzer();
            IndexWriterConfig conf = new IndexWriterConfig(analyzer);
            conf.setOpenMode(OpenMode.CREATE_OR_APPEND);

            wrt = new IndexWriter(indexDirectory, conf);
            wrt.forceMerge(1);
            elapsed.report(LOGGER, String.format("Done reducing number of segments in index%s", projectDetail),
                    "indexer.db.reduceSegments");
        } catch (IOException e) {
            writerException = e;
            LOGGER.log(Level.SEVERE, "ERROR: reducing number of segments index", e);
        } finally {
            if (wrt != null) {
                try {
                    wrt.close();
                } catch (IOException e) {
                    if (writerException == null) {
                        writerException = e;
                    }
                    LOGGER.log(Level.WARNING,
                        "An error occurred while closing writer", e);
                }
            }
            synchronized (lock) {
                running = false;
            }
        }

        if (writerException != null) {
            throw writerException;
        }
    }

    private boolean isDirty() {
        synchronized (lock) {
            return dirty;
        }
    }

    private void setDirty() {
        synchronized (lock) {
            try {
                if (!dirty) {
                    if (!dirtyFile.createNewFile() && !dirtyFile.exists()) {
                        LOGGER.log(Level.FINE,
                                "Failed to create \"dirty-file\": {0}",
                                dirtyFile.getAbsolutePath());
                    }
                    dirty = true;
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "When creating dirty file: ", e);
            }
        }
    }

    private void unsetDirty() {
        synchronized (lock) {
            if (dirtyFile.exists() && !dirtyFile.delete()) {
                LOGGER.log(Level.FINE, "Failed to remove \"dirty-file\": {0}", dirtyFile.getAbsolutePath());
            }
            dirty = false;
        }
    }

    private File whatXrefFile(String path, boolean compress) {
        String xrefPath = compress ? TandemPath.join(path, ".gz") : path;
        return new File(xrefDir, xrefPath);
    }

    /**
     * Queue the removal of xref file for given path.
     * @param path path to file under source root
     */
    private void removeXrefFile(String path) {
        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        File xrefFile = whatXrefFile(path, env.isCompressXref());
        PendingFileDeletion pending = new PendingFileDeletion(xrefFile.getAbsolutePath());
        completer.add(pending);
    }

    private void removeHistoryCacheFile(String path, boolean removeHistory) {
        HistoryGuru.getInstance().clearHistoryCacheFile(path, removeHistory);
    }

    private void removeAnnotationFile(String path) {
         HistoryGuru.getInstance().clearAnnotationCacheFile(path);
    }

    /**
     * Remove a stale file from the index database and potentially also from history cache,
     * and queue the removal of the associated xref file.
     *
     * @param removeHistory if false, do not remove history cache for this file
     * @return deleted uid (as string)
     * @throws java.io.IOException if an error occurs
     */
    private String removeFile(boolean removeHistory) throws IOException {
        String path = Util.uid2url(uidIter.term().utf8ToString());

        for (IndexChangedListener listener : listeners) {
            listener.fileRemove(path);
        }

        String deletedUid = removeFileDocUid(path);

        removeXrefFile(path);

        removeHistoryCacheFile(path, removeHistory);

        /*
         * Even when the history should not be removed (incremental reindex), annotation should,
         * because for given file it is always regenerated from scratch.
         */
        removeAnnotationFile(path);

        setDirty();

        for (IndexChangedListener listener : listeners) {
            listener.fileRemoved(path);
        }

        return deletedUid;
    }

    private String removeFileDocUid(String path) throws IOException {

        // Determine if a reversal of counts is necessary, and execute if so.
        if (isCountingDeltas) {
            postsIter = uidIter.postings(postsIter);
            StoredFields storedFields = reader.storedFields();
            while (postsIter.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
                // Read a limited-fields version of the document.
                Document doc = storedFields.document(postsIter.docID(), REVERT_COUNTS_FIELDS);
                if (doc != null) {
                    decrementLOCforDoc(path, doc);
                    break;
                }
            }
        }

        writer.deleteDocuments(new Term(QueryBuilder.U, uidIter.term()));

        return uidIter.term().utf8ToString();
    }

    private void decrementLOCforDoc(String path, Document doc) {
        NullableNumLinesLOC nullableCounts = NumLinesLOCUtil.read(doc);
        if (nullableCounts.getNumLines() != null && nullableCounts.getLOC() != null) {
            NumLinesLOC counts = new NumLinesLOC(path,
                    -nullableCounts.getNumLines(),
                    -nullableCounts.getLOC());
            countsAggregator.register(counts);
        }
    }

    /**
     * Add a file to the Lucene index (and generate a xref file).
     *
     * @param file The file to add
     * @param path The path to the file (from source root)
     * @param ctags a defined instance to use (only if its binary is not null)
     * @throws java.io.IOException if an error occurs
     * @throws InterruptedException if a timeout occurs
     */
    private void addFile(File file, String path, Ctags ctags) throws IOException, InterruptedException {

        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        AbstractAnalyzer fa = getAnalyzerFor(file, path);

        for (IndexChangedListener listener : listeners) {
            listener.fileAdd(path, fa.getClass().getSimpleName());
        }

        ctags.setTabSize(project != null ? project.getTabSize() : 0);
        if (env.getCtagsTimeout() != 0) {
            ctags.setTimeout(env.getCtagsTimeout());
        }
        fa.setCtags(ctags);
        fa.setCountsAggregator(countsAggregator);
        fa.setProject(Project.getProject(path));
        fa.setScopesEnabled(env.isScopesEnabled());
        fa.setFoldingEnabled(env.isFoldingEnabled());

        Document doc = new Document();
        CountingWriter xrefOut = null;
        try {
            String xrefAbs = null;
            File transientXref = null;
            if (env.isGenerateHtml()) {
                xrefAbs = getXrefPath(path);
                transientXref = new File(TandemPath.join(xrefAbs, PendingFileCompleter.PENDING_EXTENSION));
                xrefOut = newXrefWriter(path, transientXref, env.isCompressXref());
            }

            analyzerGuru.populateDocument(doc, file, path, fa, xrefOut);

            // Avoid producing empty xref files.
            if (xrefOut != null && xrefOut.getCount() > 0) {
                PendingFileRenaming ren = new PendingFileRenaming(xrefAbs,
                        transientXref.getAbsolutePath());
                completer.add(ren);
            } else if (xrefOut != null) {
                LOGGER.log(Level.FINER, "xref for {0} would be empty, will remove", path);
                completer.add(new PendingFileDeletion(transientXref.toString()));
            }
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "File ''{0}'' interrupted--{1}",
                new Object[]{path, e.getMessage()});
            cleanupResources(doc);
            throw e;
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "Skipped file ''{0}'' because the analyzer didn''t understand it.", path);
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, String.format("Exception from analyzer %s", fa.getClass().getName()), e);
            }
            cleanupResources(doc);
            return;
        } finally {
            fa.setCtags(null);
            fa.setCountsAggregator(null);
            if (xrefOut != null) {
                xrefOut.close();
            }
        }

        try {
            writer.addDocument(doc);
        } catch (Throwable t) {
            cleanupResources(doc);
            throw t;
        }

        setDirty();

        createAnnotationCache(file, doc);

        for (IndexChangedListener listener : listeners) {
            listener.fileAdded(path, fa.getClass().getSimpleName());
        }
    }

    private static void createAnnotationCache(File file, Document doc) {
        if (!HistoryGuru.getInstance().hasAnnotation(file, doc)) {
            LOGGER.log(Level.FINER, "skipped creating annotation cache for file ''{0}}''", file);
            return;
        }

        String lastRev = doc.get(QueryBuilder.LASTREV);
        if (lastRev != null) {
            try {
                // The last revision should be fresh. Using LatestRevisionUtil#getLatestRevision()
                // would not work here, because it uses IndexDatabase#getDocument() and the index searcher used therein
                // does not know about the updated document yet, so stale revision would be returned.
                // Instead, use the last revision (retrieved from the history in the populateDocument()
                // call above) directly.
                HistoryGuru.getInstance().createAnnotationCache(file, lastRev);
            } catch (CacheException e) {
                final String logPrefix = "failed to create annotation";
                if (e.isLogTrace()) {
                    LOGGER.log(e.getLevel(), logPrefix, e);
                } else {
                    LOGGER.log(e.getLevel(), String.format("%s: %s", logPrefix, e.getMessage()));
                }
            }
        }
    }

    @VisibleForTesting
    static AbstractAnalyzer getAnalyzerFor(File file, String path) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return AnalyzerGuru.getAnalyzer(in, path);
        }
    }

    /**
     * Do a best effort to clean up all resources allocated when populating
     * a Lucene document. On normal execution, these resources should be
     * closed automatically by the index writer once it's done with them, but
     * we may not get that far if something fails.
     *
     * @param doc the document whose resources to clean up
     */
    private static void cleanupResources(Document doc) {
        for (IndexableField f : doc) {
            // If the field takes input from a reader, close the reader.
            IOUtils.close(f.readerValue());

            // If the field takes input from a token stream, close the
            // token stream.
            if (f instanceof Field) {
                IOUtils.close(((Field) f).tokenStreamValue());
            }
        }
    }

    /**
     * Check if I should accept this file into the index database.
     * Directories are automatically accepted.
     *
     * @param file the file to check
     * @param ret defined instance whose {@code localRelPath} property will be
     * non-null afterward if and only if {@code file} is a symlink that targets
     * either a {@link Repository}-local filesystem object or the same object
     * as a previously-detected and allowed symlink. N.b. method will return
     * {@code false} if {@code ret.localRelPath} is set non-null.
     * @return a value indicating if {@code file} should be included in index
     */
    private boolean accept(File file, AcceptSymlinkRet ret) {
        ret.localRelPath = null;
        String absolutePath = file.getAbsolutePath();

        if (!pathAccepter.accept(file)) {
            return false;
        }

        if (!file.canRead()) {
            LOGGER.log(Level.WARNING, "Could not read ''{0}''", absolutePath);
            return false;
        }

        try {
            Path absolute = Paths.get(absolutePath);
            if (Files.isSymbolicLink(absolute)) {
                File canonical = file.getCanonicalFile();
                if (!absolutePath.equals(canonical.getPath()) && !acceptSymlink(absolute, canonical, ret)) {
                    if (ret.localRelPath == null) {
                        LOGGER.log(Level.FINE, "Skipped symlink ''{0}'' -> ''{1}''",
                                new Object[] {absolutePath, canonical});
                    }
                    return false;
                }
            }
            // Below will only let go files and directories, anything else is considered special and is not added.
            if (!file.isFile() && !file.isDirectory()) {
                LOGGER.log(Level.WARNING, "Ignored special file ''{0}''", absolutePath);
                return false;
            }
        } catch (IOException exp) {
            LOGGER.log(Level.WARNING, "Failed to resolve name: ''{0}''", absolutePath);
            LOGGER.log(Level.FINE, "Stack Trace: ", exp);
        }

        if (file.isDirectory()) {
            // Always accept directories so that their files can be examined.
            return true;
        }

        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        // Lookup history if indexing versioned files only.
        // Skip the lookup entirely (which is expensive) if unversioned files are allowed
        if (env.isIndexVersionedFilesOnly()) {
            if (HistoryGuru.getInstance().hasHistory(file)) {
                // Versioned files should always be accepted.
                return true;
            }
            LOGGER.log(Level.FINER, "not accepting unversioned {0}", absolutePath);
            return false;
        }
        // Unversioned files are allowed.
        return true;
    }

    /**
     * Determines if {@code file} should be accepted into the index database.
     * @param parent parent of {@code file}
     * @param file directory object under consideration
     * @param ret defined instance whose {@code localRelPath} property will be
     * non-null afterward if and only if {@code file} is a symlink that targets
     * either a {@link Repository}-local filesystem object or the same object
     * as a previously-detected and allowed symlink. N.b. method will return
     * {@code false} if {@code ret.localRelPath} is set non-null.
     * @return a value indicating if {@code file} should be included in index
     */
    private boolean accept(File parent, File file, AcceptSymlinkRet ret) {
        ret.localRelPath = null;

        try {
            File f1 = parent.getCanonicalFile();
            File f2 = file.getCanonicalFile();
            if (f1.equals(f2)) {
                LOGGER.log(Level.INFO, "Skipping links to itself...: ''{0}'' ''{1}''",
                        new Object[]{parent.getAbsolutePath(), file.getAbsolutePath()});
                return false;
            }

            // Now, let's verify that it's not a link back up the chain...
            File t1 = f1;
            while ((t1 = t1.getParentFile()) != null) {
                if (f2.equals(t1)) {
                    LOGGER.log(Level.INFO, "Skipping links to parent...: ''{0}'' ''{1}''",
                            new Object[]{parent.getAbsolutePath(), file.getAbsolutePath()});
                    return false;
                }
            }

            return accept(file, ret);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Failed to resolve name: ''{0}'' ''{1}''",
                    new Object[]{parent.getAbsolutePath(), file.getAbsolutePath()});
        }
        return false;
    }

    /**
     * Check if I should accept the path containing a symlink.
     *
     * @param absolute the path with a symlink to check
     * @param canonical the canonical file object
     * @param ret defined instance whose {@code localRelPath} property will be
     * non-null afterward if and only if {@code absolute} is a symlink that
     * targets either a {@link Repository}-local filesystem object or the same
     * object ({@code canonical}) as a previously-detected and allowed symlink.
     * N.b. method will return {@code false} if {@code ret.localRelPath} is set
     * non-null.
     * @return a value indicating if {@code file} should be included in index
     */
    private boolean acceptSymlink(Path absolute, File canonical, AcceptSymlinkRet ret) {
        ret.localRelPath = null;

        String absolute1 = absolute.toString();
        String canonical1 = canonical.getPath();
        boolean isCanonicalDir = canonical.isDirectory();
        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        IndexedSymlink indexed1;
        String absolute0;

        if (isLocal(canonical1)) {
            if (!isCanonicalDir) {
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, "Local ''{0}'' has symlink from ''{1}''",
                            new Object[] {canonical1, absolute1});
                }
                /*
                 * Always index symlinks to local files, but do not add to
                 * indexedSymlinks for a non-directory.
                 */
                return true;
            }

            /*
             * Do not index symlinks to local directories, because the
             * canonical target will be indexed on its own -- but relativize()
             * a path to be returned in ret so that a symlink can be replicated
             * in xref/.
             */
            ret.localRelPath = absolute.getParent().relativize(
                    canonical.toPath()).toString();

            // Try to put the prime absolute path into indexedSymlinks.
            try {
                String primeRelative = env.getPathRelativeToSourceRoot(canonical);
                absolute0 = env.getSourceRootPath() + primeRelative;
            } catch (ForbiddenSymlinkException | IOException e) {
                /*
                 * This is not expected, as indexDown() would have operated on
                 * the file already -- but we are forced to handle.
                 */
                LOGGER.log(Level.WARNING, String.format(
                        "Unexpected error getting relative for '%s'", canonical), e);
                absolute0 = absolute1;
            }
            indexed1 = new IndexedSymlink(absolute0, canonical1, true);
            indexedSymlinks.put(canonical1, indexed1);
            return false;
        }

        IndexedSymlink indexed0;
        if ((indexed0 = indexedSymlinks.get(canonical1)) != null) {
            if (absolute1.equals(indexed0.getAbsolute())) {
                return true;
            }

            /*
             * Do not index symlinks to external directories already indexed
             * as linked elsewhere, because the canonical target will be
             * indexed already -- but relativize() a path to be returned in ret
             * so that this second symlink can be redone as a local
             * (non-external) symlink in xref/.
             */
            ret.localRelPath = absolute.getParent().relativize(
                    Paths.get(indexed0.getAbsolute())).toString();

            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.log(Level.FINEST, "External dir ''{0}'' has symlink from ''{1}'' after first ''{2}''",
                        new Object[] {canonical1, absolute1, indexed0.getAbsolute()});
            }
            return false;
        }

        /*
         * Iterate through indexedSymlinks, which is sorted so that shorter
         * canonical entries come first, to see if the new link is a child
         * canonically.
         */
        for (IndexedSymlink a0 : indexedSymlinks.values()) {
            indexed0 = a0;
            if (!indexed0.isLocal() && canonical1.startsWith(indexed0.getCanonicalSeparated())) {
                absolute0 = indexed0.getAbsolute();
                if (!isCanonicalDir) {
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.log(Level.FINEST,
                                "External file ''{0}'' has symlink from ''{1}'' under previous ''{2}''",
                                new Object[] {canonical1, absolute1, absolute0});
                    }
                    // Do not add to indexedSymlinks for a non-directory.
                    return true;
                }

                /*
                 * See above about redoing a sourceRoot symlink as a local
                 * (non-external) symlink in xref/.
                 */
                Path abs0 = Paths.get(absolute0, canonical1.substring(
                        indexed0.getCanonicalSeparated().length()));
                ret.localRelPath = absolute.getParent().relativize(abs0).toString();

                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST,
                            "External dir ''{0}'' has symlink from ''{1}'' under previous ''{2}''",
                            new Object[] {canonical1, absolute1, absolute0});
                }
                return false;
            }
        }

        Set<String> canonicalRoots = env.getCanonicalRoots();
        for (String canonicalRoot : canonicalRoots) {
            if (canonical1.startsWith(canonicalRoot)) {
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, "Allowed symlink ''{0}'' per canonical root ''{1}''",
                            new Object[] {absolute1, canonical1});
                }
                if (isCanonicalDir) {
                    indexed1 = new IndexedSymlink(absolute1, canonical1, false);
                    indexedSymlinks.put(canonical1, indexed1);
                }
                return true;
            }
        }

        Set<String> allowedSymlinks = env.getAllowedSymlinks();
        for (String allowedSymlink : allowedSymlinks) {
            String allowedTarget;
            try {
                allowedTarget = new File(allowedSymlink).getCanonicalPath();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "unresolvable symlink: ''{0}''", allowedSymlink);
                continue;
            }
            /*
             * The following canonical check is sufficient because indexDown()
             * traverses top-down, and any intermediate symlinks would have
             * also been checked here for an allowed canonical match. This
             * technically means that if there is a set of redundant symlinks
             * with the same canonical target, then allowing one of the set
             * will allow all others in the set.
             */
            if (canonical1.equals(allowedTarget)) {
                if (isCanonicalDir) {
                    indexed1 = new IndexedSymlink(absolute1, canonical1, false);
                    indexedSymlinks.put(canonical1, indexed1);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a file is local to the current project. If we don't have
     * projects, check if the file is in the source root.
     *
     * @param path the path to a file
     * @return true if the file is local to the current repository
     */
    private boolean isLocal(String path) {
        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        String srcRoot = env.getSourceRootPath();

        if (path.startsWith(srcRoot + File.separator)) {
            if (env.hasProjects()) {
                String relPath = path.substring(srcRoot.length());
                // If file is under the current project, then it's local.
                return project.equals(Project.getProject(relPath));
            } else {
                // File is under source root, and we don't have projects, so
                // consider it local.
                return true;
            }
        }

        return false;
    }

    private void handleSymlink(String path, AcceptSymlinkRet ret) {
        /*
         * If ret.localRelPath is defined, then a symlink was detected but
         * not "accepted" to avoid redundancy with an already-accepted
         * canonical target. Set up for a deferred creation of a symlink
         * within xref/.
         */
        if (ret.localRelPath != null) {
            File xrefPath = new File(xrefDir, path);
            PendingSymlinkage psym = new PendingSymlinkage(xrefPath.getAbsolutePath(), ret.localRelPath);
            completer.add(psym);
        }
    }

    /**
     * Executes the first, serial stage of indexing, by recursively traversing the file system
     * and index alongside.
     * <p>Files at least are counted, and any deleted or updated files (based on
     * comparison to the Lucene index) are passed to
     * {@link #removeFile(boolean)}. New or updated files are noted for indexing.
     * @param dir the root indexDirectory to generate indexes for
     * @param parent path to parent directory
     * @param args arguments to control execution and for collecting a list of files for indexing
     * @param progress {@link Progress} instance
     */
    @VisibleForTesting
    void indexDown(File dir, String parent, IndexDownArgs args, Progress progress) throws IOException {
        AcceptSymlinkRet ret = new AcceptSymlinkRet();
        if (!accept(dir, ret)) {
            handleSymlink(parent, ret);
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            LOGGER.log(Level.SEVERE, "Failed to get file listing for ''{0}''", dir.getPath());
            return;
        }
        Arrays.sort(files, FILENAME_COMPARATOR);

        for (File file : files) {
            String path = parent + File.separator + file.getName();
            if (!accept(dir, file, ret)) {
                handleSymlink(path, ret);
            } else {
                if (file.isDirectory()) {
                    indexDown(file, path, args, progress);
                } else {
                    processFile(args, file, path);
                    progress.increment();
                }
            }
        }
    }

    /**
     * wrapper for fatal errors during indexing.
     */
    public static class IndexerFault extends RuntimeException {
        private static final long serialVersionUID = -1;

        public IndexerFault(String message) {
            super(message);
        }
    }

    /**
     * Compared with {@link #processFile(IndexDownArgs, File, String)}, this method's file/path arguments
     * represent files that have actually changed in some way, while the other method's argument represent
     * files present on disk.
     * @param args {@link IndexDownArgs} instance
     * @param file File object
     * @param path path of the file argument relative to source root (with leading slash)
     * @throws IOException on error
     */
    @VisibleForTesting
    void processFileHistoryBased(IndexDownArgs args, File file, String path) throws IOException {
        final boolean fileExists = file.exists();
        final Set<String> deletedUidsHere = new HashSet<>();
        path = Util.fixPathIfWindows(path);

        // Traverse terms until reaching document beyond path of given file.
        while (uidIter != null && uidIter.term() != null && uidIter.term().compareTo(emptyBR) != 0
                && FILEPATH_COMPARATOR.compare(
                        Path.of(Util.uid2url(uidIter.term().utf8ToString())),
                        Path.of(path)) <= 0) {

            if (deletedUids.contains(uidIter.term().utf8ToString())) {
                logIgnoredUid(uidIter.term().utf8ToString());
                BytesRef next = uidIter.next();
                if (next == null) {
                    uidIter = null;
                }
                continue;
            }

            /*
             * Possibly short-circuit to force reindexing of prior-version indexes.
             */
            String termPath = Util.uid2url(uidIter.term().utf8ToString());
            if (!termPath.equals(path)) {
                // A file that was not changed.
                File termFile = new File(RuntimeEnvironment.getInstance().getSourceRootFile(), termPath);
                boolean matchOK = (isWithDirectoryCounts || isCountingDeltas) &&
                        checkSettings(termFile, termPath);
                if (!matchOK) {
                    deletedUidsHere.add(removeFile(false));
                    addWorkHistoryBased(args, termFile, termPath);
                }
            } else {
                deletedUidsHere.add(removeFile(!fileExists));
            }

            BytesRef next = uidIter.next();
            if (next == null) {
                uidIter = null;
            }
        }

        // This function would not be called if the file was not changed in some way (including deletion).
        // That said, it is necessary to check whether the file can be accepted. This is done in the function below.
        // Also, allow for broken symbolic links (File.exists() returns false for these).
        if (fileExists || Files.isSymbolicLink(file.toPath())) {
            // This assumes that the last modified time is indeed what the indexer uses when adding the document.
            String time = DateTools.timeToString(file.lastModified(), DateTools.Resolution.MILLISECOND);
            if (deletedUidsHere.contains(Util.path2uid(path, time))) {
                //
                // Adding document with the same date of a pre-existing document which is being removed
                // will lead to index corruption (duplicate documents). Hence, make the indexer to fail hard.
                //
                throw new IndexerFault(
                        String.format("attempting to add file '%s' with date matching deleted document: %s",
                                path, time));
            }

            addWorkHistoryBased(args, file, path);
        }
    }

    /**
     * Check if file can be accepted into the index database. If yes, change the {@code args} argument appropriately.
     * @param args {@link IndexDownArgs} instance to which an entry will be added if deemed acceptable
     * @param file file object
     * @param path path of the file relative to given source root (not necessarily global source root)
     */
    private void addWorkHistoryBased(IndexDownArgs args, File file, String path) {
        AcceptSymlinkRet ret = new AcceptSymlinkRet();
        if (accept(file, ret)) {
            // accept() returns true for directories because it was made to work with indexDown().
            if (file.isDirectory()) {
                LOGGER.log(Level.FINER, "not accepting directory ''{0}'' into the index", file);
                return;
            }

            args.curCount++;
            args.works.add(new IndexFileWork(file, path));
        } else {
            handleSymlink(file.getParent(), ret);
        }
    }

    /**
     * Process a file on disk w.r.t. index.
     * @param args {@link IndexDownArgs} instance
     * @param file File object
     * @param path path corresponding to the file parameter, relative to source root (with leading slash)
     * @throws IOException on error
     */
    @VisibleForTesting
    void processFile(IndexDownArgs args, File file, String path) throws IOException {
        if (uidIter != null) {
            path = Util.fixPathIfWindows(path);
            String uid = Util.path2uid(path,
                DateTools.timeToString(file.lastModified(),
                DateTools.Resolution.MILLISECOND)); // construct uid for doc
            BytesRef buid = new BytesRef(uid);
            // Traverse terms that have smaller UID than the current file,
            // i.e. given the ordering they positioned before the file,
            // or it is the file that has been modified.
            while (uidIter != null && uidIter.term() != null
                    && uidIter.term().compareTo(emptyBR) != 0
                    && uidIter.term().compareTo(buid) < 0) {

                if (deletedUids.contains(uidIter.term().utf8ToString())) {
                    logIgnoredUid(uidIter.term().utf8ToString());
                    BytesRef next = uidIter.next();
                    if (next == null) {
                        uidIter = null;
                    }
                    continue;
                }

                // If the term's path matches path of currently processed file,
                // it is clear that the file has been modified and thus
                // removeFile() will be followed by call to addFile() in indexParallel().
                // In such case, instruct removeFile() not to remove history
                // cache for the file so that incremental history cache
                // generation works.
                String termPath = Util.uid2url(uidIter.term().utf8ToString());
                removeFile(!termPath.equals(path));

                BytesRef next = uidIter.next();
                if (next == null) {
                    uidIter = null;
                }
            }

            // If the file was not modified, probably skip to the next one.
            if (uidIter != null && uidIter.term() != null && uidIter.term().bytesEquals(buid)) {
                if (deletedUids.contains(uidIter.term().utf8ToString())) {
                    logIgnoredUid(uidIter.term().utf8ToString());
                    BytesRef next = uidIter.next();
                    if (next == null) {
                        uidIter = null;
                    }
                    return;
                }

                /*
                 * Possibly short-circuit to force reindexing of prior-version indexes.
                 */
                boolean matchOK = (isWithDirectoryCounts || isCountingDeltas) &&
                        checkSettings(file, path);
                if (!matchOK) {
                    removeFile(false);
                }

                BytesRef next = uidIter.next();
                if (next == null) {
                    uidIter = null;
                }

                if (matchOK) {
                    return;
                }
            }
        }

        args.curCount++;
        args.works.add(new IndexFileWork(file, path));
    }

    /**
     * Executes the second, parallel stage of indexing.
     * @param dir the parent directory (when appended to SOURCE_ROOT)
     * @param args contains a list of files to index, found during the earlier stage
     * @throws IndexerException in case the indexing failed or was interrupted
     */
    private void indexParallel(String dir, IndexDownArgs args) throws IndexerException {

        int worksCount = args.works.size();
        if (worksCount < 1) {
            return;
        }

        AtomicInteger successCounter = new AtomicInteger();
        AtomicInteger currentCounter = new AtomicInteger();
        AtomicInteger alreadyClosedCounter = new AtomicInteger();
        IndexerParallelizer parallelizer = RuntimeEnvironment.getInstance().getIndexerParallelizer();
        ObjectPool<Ctags> ctagsPool = parallelizer.getCtagsPool();

        Map<Boolean, List<IndexFileWork>> bySuccess = new HashMap<>();
        try (Progress progress = new Progress(LOGGER, String.format("indexing '%s'", dir), worksCount)) {
            Set<Callable<IndexFileWork>> callables = args.works.stream().
                    <Callable<IndexFileWork>>map(x -> () -> {
                        int tries = 0;
                        Ctags pctags = null;
                        while (true) {
                            try {
                                if (alreadyClosedCounter.get() > 0) {
                                    x.ret = false;
                                } else {
                                    pctags = ctagsPool.get();
                                    addFile(x.file, x.path, pctags);
                                    successCounter.incrementAndGet();
                                    x.ret = true;
                                }
                            } catch (AlreadyClosedException e) {
                                alreadyClosedCounter.incrementAndGet();
                                String errmsg = String.format("ERROR addFile(): '%s'", x.file);
                                LOGGER.log(Level.SEVERE, errmsg, e);
                                x.exception = e;
                                x.ret = false;
                            } catch (InterruptedException e) {
                                // Allow one retry if interrupted
                                if (++tries <= 1) {
                                    continue;
                                }
                                LOGGER.log(Level.WARNING, "No retry: ''{0}''", x.file);
                                x.exception = e;
                                x.ret = false;
                            } catch (RuntimeException | IOException e) {
                                String errmsg = String.format("ERROR addFile(): '%s'", x.file);
                                LOGGER.log(Level.WARNING, errmsg, e);
                                x.exception = e;
                                x.ret = false;
                            } finally {
                                if (pctags != null) {
                                    pctags.reset();
                                    ctagsPool.release(pctags);
                                }
                            }

                            progress.increment();
                            return x;
                        }
                    }).
                    collect(Collectors.toSet());
            List<Future<IndexFileWork>> futures = parallelizer.getIndexWorkExecutor().invokeAll(callables);
            for (var future : futures) {
                IndexFileWork work = future.get();
                bySuccess.computeIfAbsent(work.ret, key -> new ArrayList<>()).add(work);
            }
        } catch (InterruptedException | ExecutionException e) {
            int successCount = successCounter.intValue();
            double successPct = 100.0 * successCount / worksCount;
            LOGGER.log(Level.SEVERE, String.format("%d successes (%.1f%%) after aborting parallel-indexing",
                    successCount, successPct));
            throw new IndexerException(e);
        }

        args.curCount = currentCounter.intValue();

        int failureCount = worksCount - Optional.ofNullable(bySuccess.get(Boolean.TRUE))
                .map(List::size)
                .orElse(0);
        if (failureCount > 0) {
            double pctFailed = 100.0 * failureCount / worksCount;
            String exmsg = String.format("%d failures (%.1f%%) while parallel-indexing", failureCount, pctFailed);
            LOGGER.log(Level.WARNING, exmsg);
        }

        /*
         * Encountering an AlreadyClosedException is severe enough to abort the
         * run, since it will fail anyway later upon trying to commit().
         */
        int numAlreadyClosed = alreadyClosedCounter.get();
        if (numAlreadyClosed > 0) {
            throw new AlreadyClosedException(String.format("count=%d", numAlreadyClosed));
        }
    }

    /**
     * Register an object to receive events when modifications is done to the
     * index database.
     *
     * @param listener the object to receive the events
     */
    public void addIndexChangedListener(IndexChangedListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Get all files in some of the index databases.
     *
     * @param subFiles Subdirectories of various projects or null or an empty list to get everything
     * @return set of files in the index databases specified by the subFiles parameter
     * @throws IOException if an error occurs
     */
    public static Set<String> getAllFiles(List<String> subFiles) throws IOException {
        Set<String> files = new HashSet<>();
        RuntimeEnvironment env = RuntimeEnvironment.getInstance();

        if (env.hasProjects()) {
            if (subFiles == null || subFiles.isEmpty()) {
                for (Project project : env.getProjectList()) {
                    IndexDatabase db = new IndexDatabase(project);
                    files.addAll(db.getFiles());
                }
            } else {
                for (String path : subFiles) {
                    Project project = Project.getProject(path);
                    if (project == null) {
                        LOGGER.log(Level.WARNING, "Could not find a project for \"{0}\"", path);
                    } else {
                        IndexDatabase db = new IndexDatabase(project);
                        files.addAll(db.getFiles());
                    }
                }
            }
        } else {
            IndexDatabase db = new IndexDatabase();
            files = db.getFiles();
        }

        return files;
    }

    /**
     * Get all files in this index database.
     *
     * @return set of files in this index database
     * @throws IOException If an IO error occurs while reading from the database
     */
    public Set<String> getFiles() throws IOException {
        IndexReader ireader = null;
        TermsEnum iter = null;
        Terms terms;
        Set<String> files = new HashSet<>();

        try {
            ireader = DirectoryReader.open(indexDirectory); // open existing index
            if (ireader.numDocs() > 0) {
                terms = MultiTerms.getTerms(ireader, QueryBuilder.U);
                iter = terms.iterator(); // init uid iterator
            }
            BytesRef term;
            while (iter != null && (term = iter.next()) != null) {
                String value = term.utf8ToString();
                if (!value.isEmpty()) {
                    files.add(Util.uid2url(value));
                }
            }
        } finally {
            if (ireader != null) {
                try {
                    ireader.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "An error occurred while closing index reader", e);
                }
            }
        }

        return files;
    }

    /**
     * Get number of documents in this index database.
     * @return number of documents
     * @throws IOException if I/O exception occurred
     */
    public int getNumFiles() throws IOException {
        IndexReader ireader = null;
        try {
            ireader = DirectoryReader.open(indexDirectory); // open existing index
            return ireader.numDocs();
        } finally {
            if (ireader != null) {
                try {
                    ireader.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "An error occurred while closing index reader", e);
                }
            }
        }
    }

    /**
     * Get the latest definitions for a file from the index.
     *
     * @param file the file whose definitions to find
     * @return definitions for the file, or {@code null} if they could not be found
     * @throws IOException if an error happens when accessing the index
     * @throws ParseException if an error happens when building the Lucene query
     * @throws ClassNotFoundException if the class for the stored definitions
     * instance cannot be found
     */
    public static Definitions getDefinitions(File file) throws ParseException, IOException, ClassNotFoundException {
        Document doc = getDocument(file);
        if (doc == null) {
            return null;
        }

        IndexableField tags = doc.getField(QueryBuilder.TAGS);
        if (tags != null) {
            return Definitions.deserialize(tags.binaryValue().bytes);
        }

        // Didn't find any definitions.
        return null;
    }

    /**
     * @param file File object for a file under source root
     * @return Document object for the file or {@code null} if no document was found
     * @throws IOException on I/O error
     * @throws ParseException on problem with building Query
     */
    @Nullable
    public static Document getDocument(File file) throws ParseException, IOException {

        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        String path;
        try {
            path = env.getPathRelativeToSourceRoot(file);
        } catch (ForbiddenSymlinkException e) {
            LOGGER.log(Level.FINER, e.getMessage());
            return null;
        }
        // Sanitize Windows path delimiters in order not to conflict with Lucene escape character.
        path = path.replace("\\", "/");

        Document doc;
        Query q = new QueryBuilder().setPath(path).build();
        SuperIndexSearcher searcher = env.getSuperIndexSearcher(file);
        try {
            Statistics stat = new Statistics();
            TopDocs top = searcher.search(q, 1);
            stat.report(LOGGER, Level.FINEST,
                    String.format("search via getDocument(%s) done (%d hits)", file, top.totalHits.value),
                    "search.latency", new String[]{"category", "getdocument",
                            "outcome", top.totalHits.value == 0 ? "empty" : "success"});
            if (top.totalHits.value == 0) {
                // No hits, no document...
                return null;
            }
            doc = searcher.storedFields().document(top.scoreDocs[0].doc);
            String foundPath = doc.get(QueryBuilder.PATH);

            // Only use the document if we found an exact match.
            if (!path.equals(foundPath)) {
                LOGGER.log(Level.FINEST, "not matching path: ''{0}'' for ''{1}''",
                        new Object[]{foundPath, path});
                return null;
            }
        } finally {
            searcher.release();
        }

        return doc;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        IndexDatabase that = (IndexDatabase) o;
        return Objects.equals(project, that.project);
    }

    @Override
    public int hashCode() {
        return Objects.hash(project);
    }

    private static class CountingWriter extends Writer {
        private long count;
        private final Writer out;

        CountingWriter(Writer out) {
            super(out);
            this.out = out;
        }

        @Override
        public void write(@NotNull char[] chars, int off, int len) throws IOException {
            out.write(Objects.requireNonNull(chars), off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }

        public long getCount() {
            return count;
        }
    }

    private String getXrefPath(String path) {
        boolean compressed = RuntimeEnvironment.getInstance().isCompressXref();
        File xrefFile = whatXrefFile(path, compressed);
        File parentFile = xrefFile.getParentFile();

        // If mkdirs() returns false, the failure is most likely
        // because the file already exists. But to check for the
        // file first and only add it if it doesn't exists would
        // only increase the file IO...
        if (!parentFile.mkdirs()) {
            assert parentFile.exists();
        }

        // Write to a pending file for later renaming.
        String xrefAbs = xrefFile.getAbsolutePath();
        return xrefAbs;
    }

    /**
     * Get a writer to which the xref can be written, or null if no xref
     * should be produced for files of this type.
     */
    private CountingWriter newXrefWriter(String path, File transientXref, boolean compressed) throws IOException {
        return new CountingWriter(new BufferedWriter(new OutputStreamWriter(compressed ?
                new GZIPOutputStream(new FileOutputStream(transientXref)) :
                new FileOutputStream(transientXref))));
    }

    final LockFactory pickLockFactory(RuntimeEnvironment env) {
        switch (env.getLuceneLocking()) {
            case ON:
            case SIMPLE:
                return SimpleFSLockFactory.INSTANCE;
            case NATIVE:
                return NativeFSLockFactory.INSTANCE;
            case OFF:
            default:
                return NoLockFactory.INSTANCE;
        }
    }

    private void finishWriting() throws IOException {
        boolean hasPendingCommit = false;
        try {
            writeAnalysisSettings();

            LOGGER.log(Level.FINE, "preparing to commit changes to {0}", this);
            writer.prepareCommit();
            hasPendingCommit = true;

            Statistics completerStat = new Statistics();
            final String logSuffix = this.project != null ? " for project " + this.project : "";
            int n = completer.complete(logSuffix);
            completerStat.report(LOGGER, Level.FINE, String.format("completed %d object(s)%s", n, logSuffix));

            // Just before commit(), reset the `hasPendingCommit' flag,
            // since after commit() is called, there is no need for
            // rollback() regardless of success.
            hasPendingCommit = false;
            writer.commit();
        } catch (RuntimeException | IOException e) {
            if (hasPendingCommit) {
                writer.rollback();
            }
            LOGGER.log(Level.WARNING,
                "An error occurred while finishing writer and completer", e);
            throw e;
        }
    }

    /**
     * Verify {@code TABSIZE}, and evaluate AnalyzerGuru version together with {@code ZVER} --
     * or return a value to indicate mismatch.
     * @param file the source file object
     * @param path the source file path
     * @return {@code false} if a mismatch is detected
     */
    @VisibleForTesting
    boolean checkSettings(File file, String path) throws IOException {

        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        boolean outIsXrefWriter = false; // potential xref writer
        int reqTabSize = project != null && project.hasTabSizeSetting() ?
            project.getTabSize() : 0;
        Integer actTabSize = settings.getTabSize();
        if (actTabSize != null && !actTabSize.equals(reqTabSize)) {
            LOGGER.log(Level.FINE, "Tabsize mismatch: ''{0}''", path);
            return false;
        }

        int n = 0;
        postsIter = uidIter.postings(postsIter);
        StoredFields storedFields = reader.storedFields();
        while (postsIter.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
            ++n;
            // Read a limited-fields version of the document.
            Document doc = storedFields.document(postsIter.docID(), CHECK_FIELDS);
            if (doc == null) {
                LOGGER.log(Level.FINER, "No Document for ''{0}''", path);
                continue;
            }

            long reqGuruVersion = AnalyzerGuru.getVersionNo();
            Long actGuruVersion = settings.getAnalyzerGuruVersion();
            /*
             * For an older OpenGrok index that does not yet have a defined,
             * stored analyzerGuruVersion, break so that no extra work is done.
             * After a re-index, the guru version check will be active.
             */
            if (actGuruVersion == null) {
                break;
            }

            AbstractAnalyzer fa = null;
            String fileTypeName;
            if (actGuruVersion.equals(reqGuruVersion)) {
                fileTypeName = doc.get(QueryBuilder.TYPE);
                if (fileTypeName == null) {
                    // (Should not get here, but break just in case.)
                    LOGGER.log(Level.WARNING, "Missing TYPE field: ''{0}''", path);
                    break;
                }

                AnalyzerFactory fac = AnalyzerGuru.findByFileTypeName(fileTypeName);
                if (fac != null) {
                    fa = fac.getAnalyzer();
                }
            } else {
                /*
                 * If the stored guru version does not match, re-verify the
                 * selection of analyzer or return a value to indicate the
                 * analyzer is now mis-matched.
                 */
                LOGGER.log(Level.FINER, "Guru version mismatch: ''{0}''", path);

                fa = getAnalyzerFor(file, path);
                fileTypeName = fa.getFileTypeName();
                String oldTypeName = doc.get(QueryBuilder.TYPE);
                if (!fileTypeName.equals(oldTypeName)) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(Level.FINE, "Changed {0} to {1}: ''{2}''",
                            new Object[]{oldTypeName, fileTypeName, path});
                    }
                    return false;
                }
            }

            // Verify Analyzer version, or return a value to indicate mismatch.
            long reqVersion = AnalyzerGuru.getAnalyzerVersionNo(fileTypeName);
            Long actVersion = settings.getAnalyzerVersion(fileTypeName);
            if (actVersion == null || !actVersion.equals(reqVersion)) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "{0} version mismatch: ''{1}''",
                        new Object[]{fileTypeName, path});
                }
                return false;
            }

            if (fa != null) {
                outIsXrefWriter = true;
            }

            // The versions checks have passed.
            break;
        }
        if (n < 1) {
            LOGGER.log(Level.FINER, "Missing index Documents: ''{0}''", path);
            return false;
        }

        // If the economy mode is on, this should be treated as a match.
        if (!env.isGenerateHtml()) {
            if (xrefExistsFor(path)) {
                LOGGER.log(Level.FINEST, "Extraneous ''{0}'' , removing its xref file", path);
                removeXrefFile(path);
            }
            return true;
        }

        return (!outIsXrefWriter || xrefExistsFor(path));
    }

    private void writeAnalysisSettings() throws IOException {
        settings = new IndexAnalysisSettings3();
        settings.setProjectName(project != null ? project.getName() : null);
        settings.setTabSize(project != null && project.hasTabSizeSetting() ?
            project.getTabSize() : 0);
        settings.setAnalyzerGuruVersion(AnalyzerGuru.getVersionNo());
        settings.setAnalyzersVersions(AnalyzerGuru.getAnalyzersVersionNos());
        settings.setIndexedSymlinks(indexedSymlinks);

        IndexAnalysisSettingsAccessor dao = new IndexAnalysisSettingsAccessor();
        dao.write(writer, settings);
    }

    private IndexAnalysisSettings3 readAnalysisSettings() throws IOException {
        IndexAnalysisSettingsAccessor dao = new IndexAnalysisSettingsAccessor();
        return dao.read(reader);
    }

    private boolean xrefExistsFor(String path) {
        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        File xrefFile = whatXrefFile(path, env.isCompressXref());
        if (!xrefFile.exists()) {
            LOGGER.log(Level.FINEST, "Missing {0}", xrefFile);
            return false;
        }

        return true;
    }

    private static class AcceptSymlinkRet {
        String localRelPath;
    }

    @Override
    public String toString() {
        if (this.project != null) {
            return "index database for project '" + this.project.getName() + "'";
        }

        return "global index database";
    }
}

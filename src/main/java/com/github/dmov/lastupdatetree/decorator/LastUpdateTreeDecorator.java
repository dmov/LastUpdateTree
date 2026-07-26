package com.github.dmov.lastupdatetree.decorator;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LastUpdateTreeDecorator implements ProjectViewNodeDecorator {
    private static final Logger LOG = Logger.getInstance(LastUpdateTreeDecorator.class);

    public static final String LOADING_STRING = "...";

    // TODO: put these values to user settings
    public static final int MAX_CACHE_SIZE = 10_000; // should be enough for most cases? ~2MB?
    public static final int CACHE_EXPIRE_MINS = 60;
    public static final int MAX_CURRENT_LOADINGS_SIZE = 50;

    private static final Cache<String, String> cache = CacheBuilder.newBuilder()
            .maximumSize(MAX_CACHE_SIZE)
            .expireAfterWrite(CACHE_EXPIRE_MINS, TimeUnit.MINUTES)
            .build();

    private final Set<String> currentLoadings = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<ScheduledFuture<?>> refreshTaskRef = new AtomicReference<>(); // one more additional refresh, if needed

    public static void clearCache() {
        cache.cleanUp();
        LOG.info("Cache was cleaned up");
    }
    
    @Override
    public void decorate(@NotNull ProjectViewNode<?> node, @NotNull PresentationData presentationData) {
        final VirtualFile file = node.getVirtualFile();
        if (file == null || file.isDirectory()) {
            // skip
            return;
        }

        final Project project = node.getProject();
        if (project == null || project.isDisposed()) {
            // skip
            return;
        }

        final GitRepositoryManager repoManager = GitRepositoryManager.getInstance(project);
        if (repoManager.getRepositories().isEmpty()) {
            // skip
            return;
        }

        // we are here in two cases:
        // 1) "auto" refresh happened => no need in additional refresh task => call cancel
        // 2) refresh task happened => call cancel will not take an effect
        // so in general, this mechanism helps to additionally refresh view only if it was not refreshed automatically
        final ScheduledFuture<?> old = refreshTaskRef.getAndSet(null);
        if (old != null) {
            old.cancel(false);
        }

        final String path = file.getPath();

        final String date = cache.getIfPresent(path);
        if (date != null) {
            presentationData.setLocationString(date);
            return;
        }

        if (currentLoadings.size() > MAX_CURRENT_LOADINGS_SIZE || !currentLoadings.add(path)) {
            // too many current loads now OR still loading this file => skip
            presentationData.setLocationString(LOADING_STRING);
            return;
        }

        presentationData.setLocationString(LOADING_STRING);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                // saving even empty strings to the cache, to prevent reloading a file
                cache.put(path, getLastCommitDate(project, file, repoManager));
                rescheduleRefresh(project);
            } finally {
                currentLoadings.remove(path);
            }
        });
    }

    private void rescheduleRefresh(Project project) {
        final ScheduledFuture<?> old = refreshTaskRef.getAndSet(
                scheduledExecutorService.schedule(
                        doRefresh(project)
                        , 3
                        , TimeUnit.SECONDS
                )
        );
        if (old != null) {
            old.cancel(false);
        }
    }

    private @NotNull Runnable doRefresh(Project project) {
        return () ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) {
                        LOG.info("Additional refresh happened");
                        ProjectView.getInstance(project).refresh();
                    }
                });
    }

    private @NotNull String getLastCommitDate(Project project, VirtualFile file, GitRepositoryManager repoManager) {
        try {
            final GitRepository fileRepo = repoManager.getRepositoryForFile(file);
            if (fileRepo == null) {
                return "";
            }

            final GitLineHandler handler = new GitLineHandler(project, fileRepo.getRoot(), GitCommand.LOG);
            handler.addParameters("-1", "--format=%cd", "--date=format:%d.%m.%Y %H:%M", "--", file.getPath());

            final GitCommandResult result = Git.getInstance().runCommand(handler);
            if (result.success()) {
                final List<String> resultOutput = result.getOutput();
                if (!resultOutput.isEmpty()) {
                    final String lastCommitDateString = resultOutput.getFirst();
                    if (lastCommitDateString != null) {
                        return lastCommitDateString;
                    }
                }
            }
            return "";
        } catch (Exception e) {
            // NOTE: I intentionally do not put info about the file into the log
            LOG.error("Exception happened while getting last commit date", e);
            return "";
        }
    }
}
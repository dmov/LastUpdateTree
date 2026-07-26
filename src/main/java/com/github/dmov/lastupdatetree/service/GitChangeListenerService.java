package com.github.dmov.lastupdatetree.service;

import com.github.dmov.lastupdatetree.decorator.LastUpdateTreeDecorator;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBusConnection;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryChangeListener;

@Service(Service.Level.PROJECT)
public final class GitChangeListenerService implements Disposable {
    private final MessageBusConnection connection;

    public GitChangeListenerService(Project project) {
        connection = project.getMessageBus().connect(this);

        connection.subscribe(
                GitRepository.GIT_REPO_CHANGE,
                (GitRepositoryChangeListener) repository -> {
                    LastUpdateTreeDecorator.clearCache();
                    ProjectView.getInstance(project).refresh();
                }
        );
    }

    @Override
    public void dispose() {
        if (connection != null) {
            connection.dispose();
        }
    }
}

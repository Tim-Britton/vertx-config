/*
 * Copyright (c) 2014 Red Hat, Inc. and others
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */

package io.vertx.config.git;

import io.vertx.config.spi.ConfigStore;
import io.vertx.config.spi.utils.FileSet;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class GitConfigStore implements ConfigStore {

  private final static Logger LOGGER
    = LoggerFactory.getLogger(GitConfigStore.class);

  private final Vertx vertx;
  private final File path;
  private final List<FileSet> filesets = new ArrayList<>();
  private final String url;
  private final String branch;
  private final String remote;
  private final Git git;
  private final CredentialsProvider credentialProvider;

  public GitConfigStore(Vertx vertx, JsonObject configuration) {
    this.vertx = vertx;

    String path = Objects.requireNonNull(configuration.getString("path"),
      "The `path` configuration is required.");
    this.path = new File(path);
    if (this.path.isFile()) {
      throw new IllegalArgumentException("The `path` must not be a file");
    }

    JsonArray filesets = Objects.requireNonNull(configuration
        .getJsonArray("filesets"),
      "The `filesets` element is required.");

    for (Object o : filesets) {
      JsonObject json = (JsonObject) o;
      FileSet set = new FileSet(vertx, this.path, json);
      this.filesets.add(set);
    }

    // Git repository
    url = Objects.requireNonNull(configuration.getString("url"),
      "The `url` configuration (Git repository location) is required.");
    branch = configuration.getString("branch", "master");
    remote = configuration.getString("remote", "origin");

    if (Objects.nonNull(configuration.getString("user")) &&
	       Objects.nonNull(configuration.getString("password"))) {
      credentialProvider = new UsernamePasswordCredentialsProvider(
        configuration.getString("user"), configuration.getString("password"));
    } else {
      credentialProvider = null;
    }

    try {
      git = initializeGit();
    } catch (Exception e) {
      throw new VertxException("Unable to initialize the Git repository", e);
    }
  }

  private Git initializeGit() throws IOException, GitAPIException {
    if (path.isDirectory()) {
      Git git = Git.open(path);
      String current = git.getRepository().getBranch();
      if (branch.equalsIgnoreCase(current)) {
        PullResult pull = git.pull().setRemote(remote).setCredentialsProvider(credentialProvider).call();
        if (!pull.isSuccessful()) {
          LOGGER.warn("Unable to pull the branch + '" + branch +
            "' from the remote repository '" + remote + "'");
        }
        return git;
      } else {
        git.checkout().
          setName(branch).
          setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK).
          setStartPoint(remote + "/" + branch).
          call();
        return git;
      }
    } else {
      return Git.cloneRepository()
        .setURI(url)
        .setBranch(branch)
        .setRemote(remote)
        .setDirectory(path)
        .setCredentialsProvider(credentialProvider)
        .call();
    }
  }


  @Override
  public void get(Handler<AsyncResult<Buffer>> completionHandler) {
    update()   // Update repository
      .compose(v -> read()) // Read files
      .compose(this::compute)  // Compute the merged json
      .setHandler(completionHandler); // Forward
  }

  private Future<Buffer> compute(List<File> files) {
    Future<Buffer> result = Future.future();

    List<Future> futures = new ArrayList<>();
    for (FileSet set : filesets) {
      Future<JsonObject> future = Future.future();
      set.buildConfiguration(files, json -> {
        if (json.failed()) {
          future.fail(json.cause());
        } else {
          future.complete(json.result());
        }
      });
      futures.add(future);
    }

    CompositeFuture.all(futures).setHandler(cf -> {
      if (cf.failed()) {
        result.fail(cf.cause());
      } else {
        JsonObject json = new JsonObject();
        futures.stream().map(f -> (JsonObject) f.result())
          .forEach(json::mergeIn);
        result.complete(Buffer.buffer(json.encode()));
      }
    });

    return result;
  }

  private Future<Void> update() {
    Future<Void> result = Future.future();
    vertx.executeBlocking(
      future -> {
        PullResult call;
        try {
          call = git.pull().setRemote(remote).setRemoteBranchName(branch).call();
        } catch (GitAPIException e) {
          future.fail(e);
          return;
        }
        if (call.isSuccessful()) {
          future.complete();
        } else {
          if (call.getMergeResult() != null) {
            future.fail("Unable to merge repository - Conflicts: "
              + call.getMergeResult().getCheckoutConflicts());
          } else {
            future.fail("Unable to rebase repository - Conflicts: "
              + call.getRebaseResult().getConflicts());
          }
        }
      },
      result.completer()
    );
    return result;
  }

  private Future<List<File>> read() {
    Future<List<File>> result = Future.future();
    vertx.executeBlocking(
      fut -> {
        try {
          fut.complete(FileSet.traverse(path).stream().sorted().collect(Collectors.toList()));
        } catch (Throwable e) {
          fut.fail(e);
        }
      },
      result.completer());
    return result;
  }

  @Override
  public void close(Handler<Void> completionHandler) {
    vertx.runOnContext(v -> {
      if (git != null) {
        git.close();
      }
      completionHandler.handle(null);
    });
  }
}

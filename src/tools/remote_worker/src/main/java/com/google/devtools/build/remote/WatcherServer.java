// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.remote;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import com.google.common.base.Throwables;
import com.google.devtools.build.lib.remote.CacheNotFoundException;
import com.google.devtools.build.lib.remote.Digests;
import com.google.devtools.build.lib.remote.SimpleBlobStoreActionCache;
import com.google.devtools.build.lib.shell.AbnormalTerminationException;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.shell.CommandResult;
import com.google.devtools.build.lib.shell.TimeoutKillableObserver;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.remoteexecution.v1test.Action;
import com.google.devtools.remoteexecution.v1test.ActionResult;
import com.google.devtools.remoteexecution.v1test.Command.EnvironmentVariable;
import com.google.devtools.remoteexecution.v1test.ExecuteRequest;
import com.google.devtools.remoteexecution.v1test.ExecuteResponse;
import com.google.devtools.remoteexecution.v1test.Platform;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.google.watcher.v1.Change;
import com.google.watcher.v1.ChangeBatch;
import com.google.watcher.v1.Request;
import com.google.watcher.v1.WatcherGrpc.WatcherImplBase;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A basic implementation of an {@link WatcherImplBase} service. */
final class WatcherServer extends WatcherImplBase {
  private static final Logger logger = Logger.getLogger(WatcherServer.class.getName());

  // The name of the container image entry in the Platform proto
  // (see third_party/googleapis/devtools/remoteexecution/*/remote_execution.proto and
  // experimental_remote_platform_override in
  // src/main/java/com/google/devtools/build/lib/remote/RemoteOptions.java)
  private static final String CONTAINER_IMAGE_ENTRY_NAME = "container-image";

  // How long to wait for the uid command.
  private static final Duration uidTimeout = Durations.fromMicros(30);

  private static final int MAX_BLOB_SIZE_FOR_INLINE = 10 * 1024;
  private static final int LOCAL_EXEC_ERROR = -1;

  private final Path workPath;
  private final SimpleBlobStoreActionCache cache;
  private final RemoteWorkerOptions workerOptions;
  private final ConcurrentHashMap<String, ExecuteRequest> operationsCache;

  public WatcherServer(
      Path workPath,
      SimpleBlobStoreActionCache cache,
      RemoteWorkerOptions workerOptions,
      ConcurrentHashMap<String, ExecuteRequest> operationsCache) {
    this.workPath = workPath;
    this.cache = cache;
    this.workerOptions = workerOptions;
    this.operationsCache = operationsCache;
  }

  private Map<String, String> getEnvironmentVariables(
      com.google.devtools.remoteexecution.v1test.Command command) {
    HashMap<String, String> result = new HashMap<>();
    for (EnvironmentVariable v : command.getEnvironmentVariablesList()) {
      result.put(v.getName(), v.getValue());
    }
    return result;
  }

  // Gets the uid of the current user. If uid could not be successfully fetched (e.g., on other
  // platforms, if for some reason the timeout was not met, if "id -u" returned non-numeric
  // number, etc), logs a WARNING and return -1.
  // This is used to set "-u UID" flag for commands running inside Docker containers. There are
  // only a small handful of cases where uid is vital (e.g., if strict permissions are set on the
  // output files), so most use cases would work without setting uid.
  private long getUid() {
    Command cmd = new Command(new String[] {"id", "-u"});
    try {
      ByteArrayOutputStream stdout = new ByteArrayOutputStream();
      ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      cmd.execute(
          Command.NO_INPUT,
          new TimeoutKillableObserver(Durations.toMicros(uidTimeout)),
          stdout,
          stderr);
      return Long.parseLong(stdout.toString().trim());
    } catch (CommandException | NumberFormatException e) {
      logger.log(
          WARNING, "Could not get UID for passing to Docker container. Proceeding without it.", e);
      return -1;
    }
  }

  // Checks Action for docker container definition. If no docker container specified, returns
  // null. Otherwise returns docker container name from the parameters.
  private String dockerContainer(Action action) throws IllegalArgumentException {
    String result = null;
    for (Platform.Property property : action.getPlatform().getPropertiesList()) {
      if (property.getName().equals(CONTAINER_IMAGE_ENTRY_NAME)) {
        if (result != null) {
          // Multiple container name entries
          throw new IllegalArgumentException(
              "Multiple entries for " + CONTAINER_IMAGE_ENTRY_NAME + " in action.Platform");
        }
        result = property.getValue();
      }
    }
    return result;
  }

  // Takes an Action and parameters that can be used to create a Command. Returns the Command.
  // If no docker container is specified inside Action, creates a Command straight from the
  // arguments. Otherwise, returns a Command that would run the specified command inside the
  // specified docker container.
  private Command getCommand(
      Action action,
      String[] commandLineElements,
      Map<String, String> environmentVariables,
      String pathString)
      throws IllegalArgumentException {
    String container = dockerContainer(action);
    if (container == null) {
      // Was not asked to Dockerize.
      return new Command(commandLineElements, environmentVariables, new File(pathString));
    }

    // Run command inside a docker container.
    ArrayList<String> newCommandLineElements = new ArrayList<>();
    newCommandLineElements.add("docker");
    newCommandLineElements.add("run");

    long uid = getUid();
    if (uid >= 0) {
      newCommandLineElements.add("-u");
      newCommandLineElements.add(Long.toString(uid));
    }

    String dockerPathString = pathString + "-docker";
    newCommandLineElements.add("-v");
    newCommandLineElements.add(pathString + ":" + dockerPathString);
    newCommandLineElements.add("-w");
    newCommandLineElements.add(dockerPathString);

    for (Map.Entry<String, String> entry : environmentVariables.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();

      newCommandLineElements.add("-e");
      newCommandLineElements.add(key + "=" + value);
    }

    newCommandLineElements.add(container);

    newCommandLineElements.addAll(Arrays.asList(commandLineElements));

    return new Command(
        newCommandLineElements.toArray(new String[newCommandLineElements.size()]),
        null,
        new File(pathString));
  }

  private void passOutErr(byte[] stdout, byte[] stderr, ActionResult.Builder result)
      throws InterruptedException {
    if (stdout.length <= MAX_BLOB_SIZE_FOR_INLINE) {
      result.setStdoutRaw(ByteString.copyFrom(stdout));
    } else if (stdout.length > 0) {
      result.setStdoutDigest(cache.uploadBlob(stdout));
    }
    if (stderr.length <= MAX_BLOB_SIZE_FOR_INLINE) {
      result.setStderrRaw(ByteString.copyFrom(stderr));
    } else if (stderr.length > 0) {
      result.setStderrDigest(cache.uploadBlob(stderr));
    }
  }

  public ActionResult execute(Action action, Path execRoot)
      throws IOException, InterruptedException, IllegalArgumentException, CacheNotFoundException {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    ActionResult.Builder result = ActionResult.newBuilder();
    com.google.devtools.remoteexecution.v1test.Command command =
        com.google.devtools.remoteexecution.v1test.Command.parseFrom(
            cache.downloadBlob(action.getCommandDigest()));
    cache.downloadTree(action.getInputRootDigest(), execRoot);

    List<Path> outputs = new ArrayList<>(action.getOutputFilesList().size());
    for (String output : action.getOutputFilesList()) {
      Path file = execRoot.getRelative(output);
      if (file.exists()) {
        throw new FileAlreadyExistsException("Output file already exists: " + file);
      }
      FileSystemUtils.createDirectoryAndParents(file.getParentDirectory());
      outputs.add(file);
    }
    // TODO(olaola): support output directories.

    // TODO(ulfjack): This is basically a copy of LocalSpawnRunner. Ideally, we'd use that
    // implementation instead of copying it.
    Command cmd =
        getCommand(
            action,
            command.getArgumentsList().toArray(new String[] {}),
            getEnvironmentVariables(command),
            execRoot.getPathString());
    long startTime = System.currentTimeMillis();
    CommandResult cmdResult = null;
    try {
      cmdResult = cmd.execute(Command.NO_INPUT, Command.NO_OBSERVER, stdout, stderr, true);
    } catch (AbnormalTerminationException e) {
      cmdResult = e.getResult();
    } catch (CommandException e) {
      // At the time this comment was written, this must be a ExecFailedException encapsulating
      // an IOException from the underlying Subprocess.Factory.
    }
    final int timeoutSeconds = 60 * 15;
    // TODO(ulfjack): Timeout is specified in ExecuteRequest, but not passed in yet.
    boolean wasTimeout =
        cmdResult != null && cmdResult.getTerminationStatus().timedout()
            || wasTimeout(timeoutSeconds, System.currentTimeMillis() - startTime);
    int exitCode;
    if (wasTimeout) {
      final String errMessage =
          String.format(
              "Command:\n%s\nexceeded deadline of %d seconds.",
              Arrays.toString(command.getArgumentsList().toArray()), timeoutSeconds);
      logger.warning(errMessage);
      throw StatusProto.toStatusRuntimeException(
          Status.newBuilder()
              .setCode(Code.DEADLINE_EXCEEDED.getNumber())
              .setMessage(errMessage)
              .build());
    } else if (cmdResult == null) {
      exitCode = LOCAL_EXEC_ERROR;
    } else {
      exitCode = cmdResult.getTerminationStatus().getRawExitCode();
    }

    passOutErr(stdout.toByteArray(), stderr.toByteArray(), result);
    cache.uploadAllResults(execRoot, outputs, result);
    ActionResult finalResult = result.setExitCode(exitCode).build();
    if (exitCode == 0) {
      cache.setCachedActionResult(Digests.computeActionKey(action), finalResult);
    }
    return finalResult;
  }

  private boolean wasTimeout(int timeoutSeconds, long wallTimeMillis) {
    return timeoutSeconds > 0 && wallTimeMillis / 1000.0 > timeoutSeconds;
  }

  public void watch(Request wr, StreamObserver<ChangeBatch> responseObserver) {
    final String opName = wr.getTarget();
    if (!operationsCache.containsKey(opName)) {
      responseObserver.onError(
          StatusProto.toStatusRuntimeException(
              Status.newBuilder()
                  .setCode(Code.NOT_FOUND.getNumber())
                  .setMessage("Operation not found: " + opName)
                  .build()));
    }
    ExecuteRequest request = operationsCache.get(opName);
    Path tempRoot = workPath.getRelative("build-" + opName);
    try {
      tempRoot.createDirectory();
      logger.log(
          FINE,
          "Work received has {0} input files and {1} output files.",
          new int[] {request.getTotalInputFileCount(), request.getAction().getOutputFilesCount()});
      ActionResult result = execute(request.getAction(), tempRoot);
      responseObserver.onNext(
          ChangeBatch.newBuilder()
              .addChanges(
                  Change.newBuilder()
                      .setState(Change.State.EXISTS)
                      .setData(
                          Any.pack(
                              Operation.newBuilder()
                                  .setName(opName)
                                  .setDone(true)
                                  .setResponse(
                                      Any.pack(
                                          ExecuteResponse.newBuilder().setResult(result).build()))
                                  .build()))
                      .build())
              .build());
      responseObserver.onCompleted();
    } catch (CacheNotFoundException e) {
      logger.log(WARNING, "Cache miss on {0}.", e.getMissingDigest());
      responseObserver.onError(StatusUtils.notFoundError(e.getMissingDigest()));
    } catch (StatusRuntimeException e) {
      responseObserver.onError(e);
    } catch (IllegalArgumentException e) {
      responseObserver.onError(
          StatusProto.toStatusRuntimeException(
              Status.newBuilder()
                  .setCode(Code.INVALID_ARGUMENT.getNumber())
                  .setMessage(e.toString())
                  .build()));
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Work failed.", e);
      responseObserver.onError(StatusUtils.internalError(e));
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } finally {
      operationsCache.remove(opName);
      if (workerOptions.debug) {
        logger.log(INFO, "Preserving work directory {0}.", tempRoot);
      } else {
        try {
          FileSystemUtils.deleteTree(tempRoot);
        } catch (IOException e) {
          throw new RuntimeException(
              String.format(
                  "Failed to delete tmp directory %s: %s",
                  tempRoot, Throwables.getStackTraceAsString(e)));
        }
      }
    }
  }
}

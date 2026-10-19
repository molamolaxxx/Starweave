package com.mola.cmd.proxy.app.acp.observation;

import com.google.gson.JsonObject;
import com.mola.starweave.agent.NodeObservationRunner;
import java.nio.file.Path;

/** Local facade; remote SDK uses the same bounded Node runner. */
public final class ObservationScriptRunner {
    public static final int MAX_RESULT_BYTES = NodeObservationRunner.MAX_RESULT_BYTES;
    public static final int MAX_SCRIPT_BYTES = NodeObservationRunner.MAX_SCRIPT_BYTES;
    private final NodeObservationRunner delegate;
    public ObservationScriptRunner() { delegate = new NodeObservationRunner(); }
    public ObservationScriptRunner(long timeoutMillis) { delegate = new NodeObservationRunner(timeoutMillis); }
    public JsonObject execute(String script, Path workDir) { return delegate.execute(script, workDir); }
    public static void validate(String script) { NodeObservationRunner.validate(script); }
}

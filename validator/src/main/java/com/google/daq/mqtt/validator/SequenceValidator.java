package com.google.daq.mqtt.validator;

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.ISO8601DateFormat;
import com.google.bos.iot.core.proxy.IotCoreClient;
import com.google.daq.mqtt.registrar.UdmiSchema.Config;
import com.google.daq.mqtt.registrar.UdmiSchema.PointsetState;
import com.google.daq.mqtt.registrar.UdmiSchema.State;
import com.google.daq.mqtt.registrar.UdmiSchema.SystemConfig;
import com.google.daq.mqtt.registrar.UdmiSchema.SystemState;
import com.google.daq.mqtt.util.CloudIotConfig;
import com.google.daq.mqtt.util.ConfigUtil;
import com.google.daq.mqtt.util.ValidatorConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.rules.Timeout;
import org.junit.runner.Description;
import org.junit.runners.model.TestTimedOutException;

public abstract class SequenceValidator {

  private static final String EMPTY_MESSAGE = "{}";
  private static final String STATE_QUERY_TOPIC = "query/state";

  private static final String CLOUD_IOT_CONFIG_FILE = "cloud_iot_config.json";
  private static final String RESULT_LOG_FILE = "RESULT.log";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .setDateFormat(new ISO8601DateFormat())
      .setSerializationInclusion(Include.NON_NULL);
  public static final String RESULT_FAIL = "fail";
  public static final String RESULT_PASS = "pass";

  private static final String projectId;
  private static final String deviceId;
  private static final String siteModel;
  private static final String registryId;
  private static final String serial_no;
  private static final File deviceOutputDir;
  private static final File resultSummary;
  private static final IotCoreClient client;
  private static final File CONFIG_FILE = new File("validator_config.json");
  public static final String RESULT_FORMAT = "RESULT %s %s %s%n";
  public static final int INITIAL_MIN_LOGLEVEL = 400;

  protected Config deviceConfig;
  protected State deviceState;


  public static final String TESTS_OUT_DIR = "tests";

  // Because of the way tests are run and configured, these parameters need to be
  // a singleton to avoid runtime conflicts.
  static {
    final String key_file;
    try {
      ValidatorConfig validatorConfig = ConfigUtil.readValidatorConfig(CONFIG_FILE);
      siteModel = checkNotNull(validatorConfig.site_model, "site_model not defined");
      deviceId = checkNotNull(validatorConfig.device_id, "device_id not defined");
      projectId = checkNotNull(validatorConfig.project_id, "project_id not defined");
      serial_no = checkNotNull(validatorConfig.serial_no, "serial_no not defined");
      key_file = checkNotNull(validatorConfig.key_file, "key_file not defined");
    } catch (Exception e) {
      throw new RuntimeException("While loading " + CONFIG_FILE, e);
    }

    File cloudIoTConfigFile = new File(siteModel + "/" + CLOUD_IOT_CONFIG_FILE);
    final CloudIotConfig cloudIotConfig;
    try {
      cloudIotConfig = ConfigUtil.readCloudIotConfig(cloudIoTConfigFile);
      registryId = checkNotNull(cloudIotConfig.registry_id, "registry_id not defined");
    } catch (Exception e) {
      throw new RuntimeException("While loading " + cloudIoTConfigFile.getAbsolutePath());
    }

    deviceOutputDir = new File("out/devices/" + deviceId);
    try {
      deviceOutputDir.mkdirs();
      File testsOutputDir = new File(deviceOutputDir, TESTS_OUT_DIR);
      FileUtils.deleteDirectory(testsOutputDir);
    } catch (Exception e) {
      throw new RuntimeException("While preparing " + deviceOutputDir.getAbsolutePath(), e);
    }

    resultSummary = new File(deviceOutputDir, RESULT_LOG_FILE);
    resultSummary.delete();
    System.err.println("Writing results to " + resultSummary.getAbsolutePath());

    System.err.printf("Validating device %s serial %s%n", deviceId, serial_no);
    client = new IotCoreClient(projectId, cloudIotConfig, key_file);
  }

  private final Map<String, String> sentConfig = new HashMap<>();
  private final Map<String, String> receivedState = new HashMap<>();
  private String waitingCondition;
  private boolean check_serial;
  private String testName;

  @Before
  public void setUp() {
    deviceConfig = new Config();
    deviceState = new State();
    sentConfig.clear();
    receivedState.clear();
    waitingCondition = null;
    check_serial = false;
    updateConfig();
    // Do this after the initial config update to force a change.
    deviceConfig.system = new SystemConfig();
    deviceConfig.system.min_loglevel = INITIAL_MIN_LOGLEVEL;
    queryState();
  }

  @Rule
  public Timeout globalTimeout = new Timeout(60, TimeUnit.SECONDS);

  @Rule
  public TestWatcher testWatcher = new TestWatcher() {
    @Override
    protected void starting(Description description) {
      testName = description.getMethodName();
      System.err.println(getTimestamp() + " starting test " + testName);
    }

    @Override
    protected void succeeded(Description description) {
      System.err.println(getTimestamp() + " passed test " + testName);
      recordResult(RESULT_PASS, description.getMethodName(), "Sequence completed");
    }

    @Override
    protected void failed(Throwable e, Description description) {
      final String message;
      if (e instanceof TestTimedOutException) {
        message = "timeout " + waitingCondition;
      } else {
        message = e.getMessage();
      }
      System.err.println(getTimestamp() + " failed " + message);
      recordResult(RESULT_FAIL, description.getMethodName(), message);
    }
  };

  private void recordResult(String result, String methodName, String message) {
    System.err.printf(RESULT_FORMAT, result, methodName, message);
    try (PrintWriter log = new PrintWriter(new FileOutputStream(resultSummary, true))) {
      log.printf(RESULT_FORMAT, result, methodName, message);
    } catch (Exception e) {
      throw new RuntimeException("While writing report summary " + resultSummary.getAbsolutePath(),
          e);
    }
  }

  private void recordMessage(Map<String, Object> message, Map<String, String> attributes) {
    String messageBase = String
        .format("%s_%s", attributes.get("subFolder"), attributes.get("subType"));
    System.err.println(getTimestamp() + " received " + messageBase);
    String testOutDirName = TESTS_OUT_DIR + "/" + testName;
    File testOutDir = new File(deviceOutputDir, testOutDirName);
    testOutDir.mkdirs();
    File attributeFile = new File(testOutDir, messageBase + ".attr");
    try {
      OBJECT_MAPPER.writeValue(attributeFile, attributes);
    } catch (Exception e) {
      throw new RuntimeException("While writing attributes to " + attributeFile.getAbsolutePath(),
          e);
    }

    File messageFile = new File(testOutDir, messageBase + ".json");
    try {
      OBJECT_MAPPER.writeValue(messageFile, message);
    } catch (Exception e) {
      throw new RuntimeException("While writing message to " + attributeFile.getAbsolutePath(), e);
    }
  }

  private void queryState() {
    client.publish(deviceId, STATE_QUERY_TOPIC, EMPTY_MESSAGE);
  }

  @After
  public void tearDown() {
    deviceConfig = null;
    deviceState = null;
  }

  private boolean updateConfig(String subBlock, Object data) {
    try {
      String messageData = OBJECT_MAPPER.writeValueAsString(data);
      boolean updated = !messageData.equals(sentConfig.get(subBlock));
      if (updated) {
        System.err.printf("%s sending %s_%s%n", getTimestamp(), subBlock, "config");
        sentConfig.put(subBlock, messageData);
        String topic = "config/" + subBlock;
        client.publish(deviceId, topic, messageData);
      }
      return updated;
    } catch (Exception e) {
      throw new RuntimeException("While updating config block " + subBlock, e);
    }
  }

  protected void updateConfig() {
    if (updateConfig("system", deviceConfig.system) && deviceConfig.system != null) {
      System.err.println(getTimestamp() + " updated system loglevel " + deviceConfig.system.min_loglevel);
    }
    if (updateConfig("pointset", deviceConfig.pointset) && deviceConfig.pointset != null) {
      System.err.println(getTimestamp() + " updated pointset etag " + deviceConfig.pointset.etag);
    }
  }

  private <T> T messageConvert(Class<T> target, Map<String, Object> message) {
    try {
      String timestamp = (String) message.remove("timestamp");
      String messageString = OBJECT_MAPPER.writeValueAsString(message);
      return OBJECT_MAPPER.readValue(messageString, target);
    } catch (Exception e) {
      throw new RuntimeException("While converting object type " + target.getName(), e);
    }
  }

  private <T> boolean updateState(String subFolder, String expected, Class<T> target,
      Map<String, Object> message, Consumer<T> handler) {
    try {
      if (!expected.equals(subFolder)) {
        return false;
      }
      String timestamp = (String) message.remove("timestamp");
      String messageString = OBJECT_MAPPER.writeValueAsString(message);
      boolean updated = !messageString.equals(receivedState.get(subFolder));
      if (updated) {
        System.err.printf("%s updating %s state%n", getTimestamp(), subFolder);
        T state = OBJECT_MAPPER.readValue(messageString, target);
        handler.accept(state);
      }
      return updated;
    } catch (Exception e) {
      throw new RuntimeException("While converting state type " + subFolder, e);
    }
  }

  private void updateState(String subFolder, Map<String, Object> message) {
    if (updateState(subFolder, "system", SystemState.class, message,
        state -> deviceState.system = state)) {
      String last_config = deviceState.system == null ? null : deviceState.system.last_config;
      System.err.printf("%s received state last_config %s%n", getTimestamp(), last_config);
    }
    if (updateState(subFolder, "pointset", PointsetState.class, message,
        state -> deviceState.pointset = state)) {
      String etag = deviceState.pointset == null ? null : deviceState.pointset.etag;
      System.err.printf("%s received state etag %s%n", getTimestamp(), etag);
    }
    validSerialNo();
  }

  private void dumpConfigUpdate(Map<String, Object> message) {
    Config config = messageConvert(Config.class, message);
    String etag = config.pointset == null ? null : config.pointset.etag;
    System.err.println(getTimestamp() + " update config etag " + etag);
  }

  private void dumpStateUpdate(Map<String, Object> message) {
    State state = messageConvert(State.class, message);
    String etag = state.pointset == null ? null : state.pointset.etag;
    System.err.println(getTimestamp() + " update state etag " + etag);
  }

  protected boolean validSerialNo() {
    String device_serial = deviceState.system == null ? null : deviceState.system.serial_no;
    boolean serialValid = Objects.equals(serial_no, device_serial);
    if (!serialValid && check_serial) {
      throw new IllegalStateException("Unexpected serial_no " + device_serial);
    }
    check_serial = serialValid;
    return serialValid;
  }

  protected void untilTrue(Supplier<Boolean> evaluator, String description) {
    waitingCondition = "waiting for " + description;
    System.err.println(getTimestamp() + " " + waitingCondition);
    while (!evaluator.get()) {
      receiveMessage();
    }
    waitingCondition = null;
  }

  protected String getTimestamp() {
    try {
      String dateString = OBJECT_MAPPER.writeValueAsString(new Date());
      return dateString.substring(1, dateString.length() - 1);
    } catch (Exception e) {
      throw new RuntimeException("Creating timestamp", e);
    }
  }

  private void receiveMessage() {
    if (!client.isActive()) {
      throw new RuntimeException("Trying to receive message from inactive client");
    }
    client.processMessage((message, attributes) -> {
      if (!deviceId.equals(attributes.get("deviceId"))) {
        return;
      }
      recordMessage(message, attributes);
      String subType = attributes.get("subType");
      if ("states".equals(subType)) {
        updateState(attributes.get("subFolder"), message);
      } else if ("config".equals(subType)) {
        dumpConfigUpdate(message);
      } else if ("state".equals(subType)) {
        dumpStateUpdate(message);
      }
    });
  }

}

// Every actuator/PWM/timing number below is tuned to the physical hardware. Do not change.

#include <ArduinoJson.h>
#include <ESPmDNS.h>
#include <WiFi.h>
#include <WiFiManager.h>
#include <esp_random.h>
#include <mbedtls/md.h>

const uint16_t serverPort = 4211;
const char *mdnsHostname = "sesame-controller";
const char *mdnsService = "sesame";
const char *setupApName = "Sesame Controller Setup";
const int configPortalTimeout = 180;

const char *sharedSecret =
  "8b9f69c0c83cd5ef7e2844782bc32bd203c52dc7c2814ec8d51d3fd0e897494b";

WiFiServer server(serverPort);
WiFiManager wm;

const unsigned long reconnectInterval = 5000;
unsigned long lastReconnectNudge = 0;
bool wifiWasConnected = true;

const int Motor1 = 6;
const int Direction1 = 7;
const int Motor2 = 0;
const int Direction2 = 1;
const int ButtonPin = 2;

const int StatusLedPin = LED_BUILTIN;

const int pwmFreq = 4000;
const int pwmRes = 8;

const int extendDuration = 3000;
const int retractDuration = 5000;
const int defaultDuty = 180;
const int apartmentExtendDirectionLevel = LOW;
const int apartmentRetractDirectionLevel = HIGH;
const int roomExtendDirectionLevel = HIGH;
const int roomRetractDirectionLevel = LOW;

const int buttonActiveLevel = LOW;
const unsigned long buttonResetHoldTime = 3000;
const unsigned long blinkDuration = 500;

int lastButtonReading = HIGH;
unsigned long buttonPressStart = 0;
bool resetTriggered = false;

unsigned long ledOffAt = 0;

class Actuator {
private:
  enum Phase { Idle,
               Extending,
               Holding,
               Retracting };

  int pwmPin, dirPin, extLevel, retLevel, duty, extendDuration, retractDuration,
    holdDuration;
  Phase phase = Idle;
  unsigned long phaseEnd = 0;

  void setMotor(int directionLevel, int duty) {
    ledcWrite(pwmPin, 0);
    digitalWrite(dirPin, directionLevel);
    ledcWrite(pwmPin, duty);
  }

public:
  Actuator(int pwmPin, int dirPin, int extLevel, int retLevel,
           int holdDuration = 0, int extendDuration = ::extendDuration,
           int retractDuration = ::retractDuration, int duty = defaultDuty)
    : pwmPin(pwmPin), dirPin(dirPin), extLevel(extLevel), retLevel(retLevel),
      duty(duty), extendDuration(extendDuration),
      retractDuration(retractDuration), holdDuration(holdDuration) {}

  void begin() {
    pinMode(dirPin, OUTPUT);
    ledcAttach(pwmPin, pwmFreq, pwmRes);
    ledcWrite(pwmPin, 0);
  }

  bool startExtend(int duty, int duration) {
    if (phase != Idle)
      return false;

    this->duty = duty;
    setMotor(extLevel, duty);
    phase = Extending;
    phaseEnd = millis() + (unsigned long)duration;
    return true;
  }

  bool startRetract(int duty, int duration) {
    if (phase != Idle)
      return false;

    this->duty = duty;
    setMotor(retLevel, duty);
    phase = Retracting;
    phaseEnd = millis() + (unsigned long)duration;
    return true;
  }

  void update() {
    unsigned long now = millis();
    if ((long)(now - phaseEnd) < 0)
      return;

    if (phase == Extending) {
      ledcWrite(pwmPin, 0);
      phase = Holding;
      phaseEnd = now + (unsigned long)holdDuration;
      return;
    }

    if (phase == Holding) {
      setMotor(retLevel, duty);
      phase = Retracting;
      phaseEnd = now + (unsigned long)retractDuration;
      return;
    }

    if (phase == Retracting) {
      ledcWrite(pwmPin, 0);
      phase = Idle;
    }
  }

  bool pressRemote() {
    return startExtend(duty, extendDuration);
  }

  bool isBusy() const {
    return phase != Idle;
  }
};

Actuator apartmentActuator(Motor1, Direction1, apartmentExtendDirectionLevel,
                           apartmentRetractDirectionLevel, 5500, 3000, 5000,
                           180);
Actuator roomActuator(Motor2, Direction2, roomExtendDirectionLevel,
                      roomRetractDirectionLevel, 4300, 7000, 5000, 255);

void blink() {
  digitalWrite(StatusLedPin, HIGH);
  ledOffAt = millis() + blinkDuration;
}

void updateStatusLed() {
  if (ledOffAt != 0 && (long)(millis() - ledOffAt) >= 0) {
    digitalWrite(StatusLedPin, LOW);
    ledOffAt = 0;
  }
}

void homeActuators() {
  apartmentActuator.startRetract(defaultDuty, retractDuration);
  roomActuator.startRetract(defaultDuty, retractDuration);
}

void homeActuatorsBlocking() {
  apartmentActuator.startRetract(defaultDuty, retractDuration);
  roomActuator.startRetract(defaultDuty, retractDuration);

  unsigned long deadline = millis() + (unsigned long)retractDuration;
  while ((long)(millis() - deadline) < 0) {
    apartmentActuator.update();
    roomActuator.update();
    yield();
  }
}

void sendResponse(WiFiClient &client, bool success, const String &message) {
  JsonDocument doc;
  doc["success"] = success;
  doc["message"] = message;
  serializeJson(doc, client);
  client.println();

  if (success) {
    blink();
  }
}

String hmacHex(const String &message) {
  uint8_t mac[32];
  mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256),
                  (const uint8_t *)sharedSecret, strlen(sharedSecret),
                  (const uint8_t *)message.c_str(), message.length(), mac);

  String hex;
  for (size_t i = 0; i < sizeof(mac); i++) {
    char byteHex[3];
    snprintf(byteHex, sizeof(byteHex), "%02x", mac[i]);
    hex += byteHex;
  }
  return hex;
}

void handleCommand(WiFiClient &client, const String &nonce, String line) {
  line.trim();

  int sep = line.lastIndexOf(' ');
  String command = (sep < 0) ? "" : line.substring(0, sep);
  if (sep < 0 || line.substring(sep + 1) != hmacHex(nonce + " " + command)) {
    sendResponse(client, false, "UNAUTHORIZED");
    return;
  }
  line = command;

  if (line == "HEARTBEAT") {
    sendResponse(client, true, "HEARTBEAT_SUCCESS");
    return;
  }

  if (line == "CONNECT") {
    homeActuators();
    sendResponse(client, true, "CONNECT_ACK");
    return;
  }

  if (line == "OPEN_APARTMENT") {
    if (apartmentActuator.pressRemote()) {
      sendResponse(client, true, "OPEN_APARTMENT_SUCCESS");
    } else {
      sendResponse(client, false, "ACTUATOR_BUSY");
    }
    return;
  }

  if (line == "OPEN_ROOM") {
    if (roomActuator.pressRemote()) {
      sendResponse(client, true, "OPEN_ROOM_SUCCESS");
    } else {
      sendResponse(client, false, "ACTUATOR_BUSY");
    }
    return;
  }

  sendResponse(client, false, "UNKNOWN_COMMAND");
}

void serviceButton() {
  unsigned long now = millis();
  int reading = digitalRead(ButtonPin);

  if (reading != lastButtonReading) {
    lastButtonReading = reading;
    if (reading == buttonActiveLevel) {
      buttonPressStart = now;
    } else {
      resetTriggered = false;
    }
  }

  if (reading == buttonActiveLevel && !resetTriggered && (now - buttonPressStart) >= buttonResetHoldTime) {
    resetTriggered = true;
    digitalWrite(StatusLedPin, HIGH);
    wm.resetSettings();
    delay(800);
    ESP.restart();
  }
}

void startMdns() {
  MDNS.end();
  if (!MDNS.begin(mdnsHostname)) {
    Serial.println("mDNS responder error");
    return;
  }
  MDNS.addService(mdnsService, "tcp", serverPort);
  Serial.print("mDNS responder started: ");
  Serial.print(mdnsHostname);
  Serial.println(".local");
}

void serviceWiFi() {
  bool nowConnected = (WiFi.status() == WL_CONNECTED);

  if (!nowConnected) {
    if (wifiWasConnected) {
      Serial.println("Reconnecting to Wi-Fi...");
    }
    unsigned long now = millis();
    if (now - lastReconnectNudge > reconnectInterval) {
      lastReconnectNudge = now;
      WiFi.reconnect();
    }
  } else if (!wifiWasConnected) {
    Serial.print("Wi-Fi reconnected. IP: ");
    Serial.println(WiFi.localIP());
    startMdns();
    homeActuators();
  }

  wifiWasConnected = nowConnected;
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  pinMode(ButtonPin, INPUT_PULLUP);
  pinMode(StatusLedPin, OUTPUT);
  digitalWrite(StatusLedPin, LOW);
  lastButtonReading = digitalRead(ButtonPin);
  buttonPressStart = millis();

  apartmentActuator.begin();
  roomActuator.begin();
  homeActuatorsBlocking();

  Serial.println("Starting WiFiManager...");
  wm.setConfigPortalTimeout(configPortalTimeout);
  bool connected = wm.autoConnect(setupApName);
  if (!connected) {
    Serial.println("Failed to connect to Wi-Fi. Rebooting...");
    delay(3000);
    ESP.restart();
  }

  Serial.println("Connected to Wi-Fi.");
  Serial.print("IP Address: ");
  Serial.println(WiFi.localIP());

  startMdns();

  server.begin();
  Serial.println("TCP server started on port 4211");
}

void loop() {
  apartmentActuator.update();
  roomActuator.update();
  updateStatusLed();
  serviceButton();
  serviceWiFi();

  WiFiClient client = server.available();
  if (client) {
    client.setTimeout(300);

    char nonce[17];
    snprintf(nonce, sizeof(nonce), "%08x%08x", esp_random(), esp_random());
    client.println(nonce);

    unsigned long deadline = millis() + 1000;  // 1 second wait
    while (!client.available() && (long)(millis() - deadline) < 0) {
      apartmentActuator.update();
      roomActuator.update();
      yield();
    }

    handleCommand(client, nonce, client.readStringUntil('\n'));
    client.stop();
  }

  yield();
}

#include <WiFi.h>
#include <ArduinoJson.h>

const char *WIFI_SSID = "AnsonMilky";
const char *WIFI_PASSWORD = "bobbysandy7";
const unsigned long wifiConnectTimeout = 30000;

WiFiServer server(4211);
bool serverStarted = false;

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

const unsigned long buttonDebounce = 80;
const unsigned long buttonTriggerCooldown = 600;
const int buttonActiveLevel = LOW;

class Actuator {
private:
  enum Phase {
    Idle,
    Extending,
    Retracting
  };

  int pwmPin, dirPin, extLevel, retLevel, duty, extendDuration, retractDuration;
  Phase phase = Idle;
  unsigned long phaseEnd = 0;

  void setMotor(int directionLevel, int duty) {
    ledcWrite(pwmPin, 0);
    digitalWrite(dirPin, directionLevel);
    ledcWrite(pwmPin, duty);
  }

public:
  Actuator(int pwmPin, int dirPin, int extLevel, int retLevel)
    : pwmPin(pwmPin), dirPin(dirPin), extLevel(extLevel), retLevel(retLevel), duty(defaultDuty), extendDuration(::extendDuration), retractDuration(::retractDuration) {}

  void begin() {
    pinMode(dirPin, OUTPUT);
    ledcAttach(pwmPin, pwmFreq, pwmRes);
    ledcWrite(pwmPin, 0);
  }

  bool startExtend(int duty, int duration) {
    if (phase != Idle) return false;

    this->duty = duty;
    setMotor(extLevel, duty);
    phase = Extending;
    phaseEnd = millis() + (unsigned long)duration;
    return true;
  }

  bool startRetract(int duty, int duration) {
    if (phase != Idle) return false;

    this->duty = duty;
    setMotor(retLevel, duty);
    phase = Retracting;
    phaseEnd = millis() + (unsigned long)duration;
    return true;
  }

  void update() {
    unsigned long now = millis();
    if ((long)(now - phaseEnd) < 0) {
      return;
    }

    if (phase == Extending) {
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


Actuator apartmentActuator(Motor1, Direction1, apartmentExtendDirectionLevel, apartmentRetractDirectionLevel);
Actuator roomActuator(Motor2, Direction2, roomExtendDirectionLevel, roomRetractDirectionLevel);

int lastButtonReading = HIGH;
int stableButtonState = HIGH;
unsigned long lastButtonEdge = 0;
unsigned long lastButtonTrigger = 0;
bool buttonReadyForNextPress = false;

void blink() {
  digitalWrite(StatusLedPin, HIGH);
  delay(500);
  digitalWrite(StatusLedPin, LOW);
}

void sendResponse(WiFiClient &client, bool success, const String &message, const String &target = "") {
  StaticJsonDocument<192> jsonDoc;
  jsonDoc["success"] = success;
  jsonDoc["message"] = message;
  if (target.length() > 0) {
    jsonDoc["target"] = target;
  }
  serializeJson(jsonDoc, client);
  client.println();

  if (success) {
    blink();
  }
}

bool connectToWiFi(const String &ssid, const String &password) {
  if (WiFi.status() == WL_CONNECTED) {
    if (!serverStarted) {
      server.begin();
      serverStarted = true;
      Serial.println("TCP server started on port 4211");
    }
    return true;
  }

  Serial.print("Connecting to: ");
  Serial.println(ssid);

  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(ssid.c_str(), password.c_str());

  unsigned long now = millis();
  while (WiFi.status() != WL_CONNECTED && (millis() - now) < wifiConnectTimeout) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("Failed to connect to Wi-Fi");
    return false;
  }

  Serial.print("Connected to: ");
  Serial.println(ssid);
  Serial.print("IP: ");
  Serial.println(WiFi.localIP());

  if (!serverStarted) {
    server.begin();
    serverStarted = true;
    Serial.println("TCP server started on port 4211");
  }

  return true;
}

bool openApartmentDoor() {
  return apartmentActuator.pressRemote();
}

bool openRoomDoor() {
  return roomActuator.pressRemote();
}

void retractBothActuatorsOnSetup() {
  apartmentActuator.startRetract(180, retractDuration);
  roomActuator.startRetract(180, retractDuration);

  unsigned long deadline = millis() + (unsigned long)retractDuration;

  while ((long)(millis() - deadline) < 0) {
    apartmentActuator.update();
    roomActuator.update();
    yield();
  }
}

void handleCommand(WiFiClient &client, String line) {
  if (line == "HEARTBEAT") {
    sendResponse(client, true, "HEARTBEAT_SUCCESS");
    return;
  }

  if (line == "OPEN_APARTMENT") {
    if (openApartmentDoor()) {
      sendResponse(client, true, "OPEN_APARTMENT_SUCCESS");
    } else {
      sendResponse(client, false, "ACTUATOR_BUSY");
    }
    return;
  }

  if (line == "OPEN_ROOM") {
    if (openRoomDoor()) {
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
    lastButtonEdge = now;
    lastButtonReading = reading;
  }

  if ((now - lastButtonEdge) >= buttonDebounce && reading != stableButtonState) {
    stableButtonState = reading;

    bool pressed = (stableButtonState == buttonActiveLevel);
    if (!pressed) {
      buttonReadyForNextPress = true;
      return;
    }

    if (buttonReadyForNextPress && (now - lastButtonTrigger) >= buttonTriggerCooldown) {
      lastButtonTrigger = now;
      buttonReadyForNextPress = false;
      retractBothActuatorsOnSetup();
    }
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  pinMode(ButtonPin, INPUT_PULLUP);

  pinMode(StatusLedPin, OUTPUT);
  digitalWrite(StatusLedPin, LOW);

  int initialButtonReading = digitalRead(ButtonPin);
  lastButtonReading = initialButtonReading;
  stableButtonState = initialButtonReading;
  lastButtonEdge = millis();
  lastButtonTrigger = 0;

  bool initiallyPressed = (initialButtonReading == buttonActiveLevel);
  buttonReadyForNextPress = !initiallyPressed;

  apartmentActuator.begin();
  roomActuator.begin();
  retractBothActuatorsOnSetup();

  connectToWiFi(WIFI_SSID, WIFI_PASSWORD);
}

void loop() {
  apartmentActuator.update();
  roomActuator.update();

  serviceButton();
  connectToWiFi(WIFI_SSID, WIFI_PASSWORD);

  WiFiClient client = server.available();
  if (client) {
    client.setTimeout(300);
    String line = client.readStringUntil('\n');
    handleCommand(client, line);
    client.stop();
  }

  yield();
}

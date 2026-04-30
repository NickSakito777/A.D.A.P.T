/*
 * ID15 Gripper Limit Test
 * Safe range: 55°(pos 626) ~ 223°(pos 2538)
 * Assembly center: 180° = pos 2047
 *
 * Flow: 2047 -> 626 (55° lower) -> 2047 -> 2538 (223° upper) -> 2047
 * Slow speed + delay so you can stop power if it hits structure.
 */

#include <SCServo.h>

SMS_STS st;

#define S_RXD 18
#define S_TXD 19

#define TEST_ID    15
#define POS_CENTER 2047  // 180° (assembly position)
#define POS_MIN    626   // 55°  (lower limit)
#define POS_MAX    2538  // 223° (upper limit)
#define SPD        300
#define ACC        10

void moveTo(s16 pos, const char* label) {
  Serial.print("-> ");
  Serial.print(label);
  Serial.print(" (pos ");
  Serial.print(pos);
  Serial.println(")");
  st.WritePosEx(TEST_ID, pos, SPD, ACC);
}

void setup() {
  Serial.begin(115200);
  Serial1.begin(1000000, SERIAL_8N1, S_RXD, S_TXD);
  st.pSerial = &Serial1;
  while(!Serial1) {}

  delay(1000);
  Serial.println("ID15 Gripper Limit Test start");

  moveTo(POS_CENTER, "180 center");
  delay(2000);

  moveTo(POS_MIN, "55 lower limit");
  delay(4000);

  moveTo(POS_CENTER, "180 center");
  delay(2000);

  moveTo(POS_MAX, "223 upper limit");
  delay(4000);

  moveTo(POS_CENTER, "180 center");
  Serial.println("Done.");
}

void loop() {
}

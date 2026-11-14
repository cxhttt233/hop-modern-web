#!/usr/bin/env bash
# Producer-attested native Hop FilterRows AND/OR + numeric CValue XML contract.
# Requires the isolated Hop 2.20 Java runtime; no shared service or workspace writes.
set -euo pipefail
RUNTIME="${HMW_HOP_RUNTIME:-/home/ubuntu/hop-modern-preview/runtime/server}"
TMP=$(mktemp -d /tmp/hmw-native-filterrows-XXXXXXXX)
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/NativeFilterRowsProof.java" <<'JAVA'
import java.util.List;
import org.apache.hop.core.Condition;
import org.apache.hop.core.Condition.CValue;
import org.apache.hop.pipeline.transforms.filterrows.FilterRowsMeta;
public final class NativeFilterRowsProof {
  private static Condition numeric(String field, Condition.Operator operator, String number) {
    Condition condition = new Condition();
    condition.setOperator(operator);
    condition.setLeftValueName(field);
    condition.setFunction(Condition.Function.LARGER);
    CValue literal = new CValue();
    literal.setName("threshold");
    literal.setType("Number");
    literal.setText(number);
    literal.setLength(10);
    literal.setPrecision(1);
    condition.setRightValue(literal);
    return condition;
  }
  public static void main(String[] ignored) throws Exception {
    Condition root = new Condition();
    Condition first = numeric("water_level", Condition.Operator.NONE, "12.5");
    Condition second = new Condition();
    second.setOperator(Condition.Operator.AND);
    second.setLeftValueName("station_status");
    second.setFunction(Condition.Function.EQUAL);
    second.setRightValueName("expected_status");
    Condition third = numeric("rainfall", Condition.Operator.OR, "5.0");
    root.getChildren().addAll(List.of(first, second, third));
    FilterRowsMeta producer = new FilterRowsMeta();
    producer.setCondition(root);
    String xml = producer.getConditionXml();
    Condition restored = new Condition(xml);
    if (restored.getChildren().size() != 3) throw new AssertionError("children lost");
    if (restored.getChildren().get(1).getOperator() != Condition.Operator.AND)
      throw new AssertionError("AND lost");
    if (restored.getChildren().get(2).getOperator() != Condition.Operator.OR)
      throw new AssertionError("OR lost");
    CValue constant = restored.getChildren().get(0).getRightValue();
    if (!"Number".equals(constant.getType()) || !"12.5".equals(constant.getText()))
      throw new AssertionError("native CValue lost");
    if (!xml.contains("<operator>AND</operator>") || !xml.contains("<operator>OR</operator>"))
      throw new AssertionError("native XML operators missing");
    System.out.println("NATIVE_FILTERROWS_CONDITION_CVALUE_AND_OR_PASS");
  }
}
JAVA
CP="$RUNTIME/classes:$RUNTIME/lib/*"
javac -proc:none --release 21 -cp "$CP" -d "$TMP" "$TMP/NativeFilterRowsProof.java"
java -Xmx128m -cp "$TMP:$CP" NativeFilterRowsProof

package io.rouz.task;

import com.google.auto.value.AutoValue;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@link AutoValue} implementation of {@link TaskId}
 */
@AutoValue
abstract class TaskIds implements TaskId, Serializable {

  abstract List<Object> args();

  static TaskId create(String name, Object... args) {
    return new AutoValue_TaskIds(
        name,
        name.hashCode() * 1000003 ^ Objects.hash(args),
        Arrays.asList(args));
  }

  @Override
  public String toString() {
    return String.format("%s(%s)#%08x", name(), argsString(), hash());
  }

  private String argsString() {
    return args().stream()
        .map(Object::toString)
        .collect(Collectors.joining(","));
  }
}

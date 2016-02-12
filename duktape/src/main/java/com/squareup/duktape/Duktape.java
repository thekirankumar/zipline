/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.duktape;

import java.io.Closeable;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** A simple EMCAScript (Javascript) interpreter. */
public final class Duktape implements Closeable {
  static {
    System.loadLibrary("duktape");
  }

  /**
   * Create a new interpreter instance. Calls to this method <strong>must</strong> matched with
   * calls to {@link #close()} on the returned instance to avoid leaking native memory.
   */
  public static Duktape create() {
    long context = createContext();
    if (context == 0) {
      throw new OutOfMemoryError("Cannot create Duktape instance");
    }
    return new Duktape(context);
  }

  private long context;

  private Duktape(long context) {
    this.context = context;
  }

  /**
   * Evaluate {@code script} and return any result.  {@code fileName} will be used in error
   * reporting.
   *
   * @throws DuktapeException if there is an error evaluating the script.
   */
  public synchronized String evaluate(String script, String fileName) {
    return evaluate(context, script, fileName);
  }
  /**
   * Evaluate {@code script} and return any result.
   *
   * @throws DuktapeException if there is an error evaluating the script.
   */
  public String evaluate(String script) {
    return evaluate(script, "?");
  }

  /**
   * Binds {@code object} to {@code name} for use in JavaScript as a global object. {@code type}
   * defines the interface implemented by {@code object} that will be accessible to JavaScript.
   * {@code type} must be an interface that does not extend any other interfaces, and cannot define
   * any overloaded methods.
   */
  public <T> void bind(String name, Class<T> type, T object) {
    if (!type.isInterface()) {
      throw new UnsupportedOperationException("Only interfaces can be bound. Received: " + type);
    }
    if (type.getInterfaces().length > 0) {
      throw new UnsupportedOperationException(type + " must not extend other interfaces.");
    }
    if (!type.isInstance(object)) {
      throw new IllegalArgumentException(object.getClass() + " is not an instance of " + type);
    }
    LinkedHashMap<String, Method> methods = new LinkedHashMap<>();
    for (Method method : type.getMethods()) {
      checkSignatureSupported(method);
      if (methods.put(method.getName(), method) != null) {
        throw new UnsupportedOperationException(method.getName() + " is overloaded in " + type);
      }
    }
    bind(context, name, object, methods.values().toArray());
  }

  /**
   * Release the native resources associated with this object. You <strong>must</strong> call this
   * method for each instance to avoid leaking native memory.
   */
  @Override public synchronized void close() {
    if (context != 0) {
      long contextToClose = context;
      context = 0;
      destroyContext(contextToClose);
    }
  }

  @Override protected synchronized void finalize() throws Throwable {
    if (context != 0) {
      Logger.getLogger(getClass().getName()).warning("Duktape instance leaked!");
    }
  }

  private static void checkSignatureSupported(Method method) {
    if (!isSupportedParameterType(method.getReturnType())
        && !void.class.equals(method.getReturnType())) {
      throw new UnsupportedOperationException(
          String.format("Return type %s on %s is not supported",
              method.getReturnType().toString(), method.getName()));
    }
    for (Class<?> parameterType : method.getParameterTypes()) {
      if (!isSupportedParameterType(parameterType)) {
        throw new UnsupportedOperationException(
            String.format("Parameter type %s on %s is not supported",
                parameterType.toString(), method.getName()));
      }
    }
  }

  /** Returns true if we support {@code: type} as parameters in calls from JavaScript. */
  private static boolean isSupportedParameterType(Class<?> type) {
    return boolean.class.equals(type)
        || int.class.equals(type)
        || double.class.equals(type)
        || String.class.equals(type);
  }

  private static native long createContext();
  private static native void destroyContext(long context);
  private static native String evaluate(long context, String sourceCode, String fileName);
  private static native void bind(long context, String name, Object object, Object[] methods);

  /** Returns the timezone offset in seconds given system time millis. */
  @SuppressWarnings("unused") // Called from native code.
  private static int getLocalTimeZoneOffset(double t) {
    int offsetMillis = TimeZone.getDefault().getOffset((long) t);
    return (int) TimeUnit.MILLISECONDS.toSeconds(offsetMillis);
  }
}

#pragma once

#include <jni.h>

namespace controlled_sandbox {

/** Observe-only: log pending Java exceptions seen by JNI ExceptionCheck/Occurred. */
bool install_jni_pending_exception_probe(JNIEnv* env);

}  // namespace controlled_sandbox

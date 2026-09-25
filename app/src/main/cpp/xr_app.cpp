// OpenXR passthrough session that shows mpv's output on a CRT placed in the room.
//
// Kotlin owns mpv and the SurfaceTexture; this file owns EGL, the OpenXR session, input and
// rendering. Everything here runs on the single render thread that calls XrNative.run().

#include <jni.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

#include "crt_scene.h"
#include "xr_actions.h"
#include "xr_math.h"

#define LOG_TAG "mpvEx-XR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define XR_CHECK(expr)                                        \
  do {                                                        \
    XrResult r_ = (expr);                                     \
    if (XR_FAILED(r_)) {                                      \
      LOGE("%s failed: %d (%s:%d)", #expr, r_, __FILE__, __LINE__); \
      return false;                                           \
    }                                                         \
  } while (0)

namespace {

std::atomic<bool> gExitRequested{false};

constexpr float kSizePresetsInches[] = {14, 20, 27, 32, 40};

constexpr int kLeft = 0, kRight = 1;

struct Bridge {
  JNIEnv* env = nullptr;
  jobject obj = nullptr;
  jmethodID onGlReady = nullptr;
  jmethodID updateVideoTexture = nullptr;
  jmethodID isPlaying = nullptr;
  jmethodID onAction = nullptr;
  jmethodID onCrtPoseChanged = nullptr;
  jmethodID onSessionEnded = nullptr;
  jfloatArray matrix = nullptr;

  bool init(JNIEnv* e, jobject bridge) {
    env = e;
    obj = bridge;
    jclass cls = env->GetObjectClass(bridge);
    onGlReady = env->GetMethodID(cls, "onGlReady", "(IF)V");
    updateVideoTexture = env->GetMethodID(cls, "updateVideoTexture", "([F)Z");
    isPlaying = env->GetMethodID(cls, "isPlaying", "()Z");
    onAction = env->GetMethodID(cls, "onAction", "(I)V");
    onCrtPoseChanged = env->GetMethodID(cls, "onCrtPoseChanged", "([F)V");
    onSessionEnded = env->GetMethodID(cls, "onSessionEnded", "()V");
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
      return false;
    }
    matrix = static_cast<jfloatArray>(env->NewGlobalRef(env->NewFloatArray(16)));
    return true;
  }

  void release() {
    if (matrix) env->DeleteGlobalRef(matrix);
    matrix = nullptr;
  }

  void check() {
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
    }
  }

  void glReady(GLuint texture, float screenAspect) {
    env->CallVoidMethod(obj, onGlReady, static_cast<jint>(texture), static_cast<jfloat>(screenAspect));
    check();
  }

  bool updateVideo(float* out) {
    bool hasFrame = env->CallBooleanMethod(obj, updateVideoTexture, matrix);
    check();
    env->GetFloatArrayRegion(matrix, 0, 16, out);
    return hasFrame;
  }

  bool playing() {
    bool p = env->CallBooleanMethod(obj, isPlaying);
    check();
    return p;
  }

  void action(jint code) {
    env->CallVoidMethod(obj, onAction, code);
    check();
  }

  void crtPoseChanged(const Pose& pose, float scale) {
    float v[8] = {pose.p.x, pose.p.y, pose.p.z, pose.q.x, pose.q.y, pose.q.z, pose.q.w, scale};
    jfloatArray arr = env->NewFloatArray(8);
    env->SetFloatArrayRegion(arr, 0, 8, v);
    env->CallVoidMethod(obj, onCrtPoseChanged, arr);
    env->DeleteLocalRef(arr);
    check();
  }

  void sessionEnded() {
    env->CallVoidMethod(obj, onSessionEnded);
    check();
  }
};

struct EyeTarget {
  XrSwapchain swapchain = XR_NULL_HANDLE;
  int32_t width = 0, height = 0;
  std::vector<XrSwapchainImageOpenGLESKHR> images;
  GLuint depth = 0;
  GLuint fbo = 0;
};

struct Hand {
  XrPath path = XR_NULL_PATH;
  XrSpace aimSpace = XR_NULL_HANDLE;
  bool active = false;
  Pose aim;
  float squeeze = 0, trigger = 0;
  XrVector2f stick{0, 0};
  bool stickClickPressed = false, primaryPressed = false, secondaryPressed = false,
       menuPressed = false;

  // Interaction state.
  bool grabbing = false;
  Pose grabOffset;
  float grabYaw = 0;
  bool triggerDown = false;
  int hoverButton = -1;
  int pressedButton = -1;
  float buttonRepeat = 0;
  int stickDir = 0;  // 0 none, 1 right, 2 left, 3 up, 4 down
  float stickRepeat = 0;
};

class XrApp {
 public:
  bool run(JNIEnv* env, jobject activity, jobject bridge, jfloatArray initialPose,
           const char* modelPath);

 private:
  Bridge bridge_;

  // EGL
  EGLDisplay display_ = EGL_NO_DISPLAY;
  EGLConfig config_ = nullptr;
  EGLContext context_ = EGL_NO_CONTEXT;
  EGLSurface pbuffer_ = EGL_NO_SURFACE;
  bool msaa_ = false;
  PFNGLFRAMEBUFFERTEXTURE2DMULTISAMPLEEXTPROC glFramebufferTexture2DMultisampleEXT_ = nullptr;
  PFNGLRENDERBUFFERSTORAGEMULTISAMPLEEXTPROC glRenderbufferStorageMultisampleEXT_ = nullptr;

  // OpenXR
  XrInstance instance_ = XR_NULL_HANDLE;
  XrSystemId system_ = XR_NULL_SYSTEM_ID;
  XrSession session_ = XR_NULL_HANDLE;
  XrSessionState state_ = XR_SESSION_STATE_UNKNOWN;
  bool running_ = false;
  bool exitRequestSent_ = false;
  XrSpace appSpace_ = XR_NULL_HANDLE;
  XrSpace viewSpace_ = XR_NULL_HANDLE;
  bool stageSpace_ = false;
  EyeTarget eyes_[2];
  int64_t colorFormat_ = GL_RGBA8;

  XrActionSet actionSet_ = XR_NULL_HANDLE;
  XrAction aimAction_ = XR_NULL_HANDLE, squeezeAction_ = XR_NULL_HANDLE,
           triggerAction_ = XR_NULL_HANDLE, stickAction_ = XR_NULL_HANDLE,
           stickClickAction_ = XR_NULL_HANDLE, primaryAction_ = XR_NULL_HANDLE,
           secondaryAction_ = XR_NULL_HANDLE, menuAction_ = XR_NULL_HANDLE,
           hapticAction_ = XR_NULL_HANDLE;
  Hand hands_[2];

  bool passthroughSupported_ = false;
  XrPassthroughFB passthrough_ = XR_NULL_HANDLE;
  XrPassthroughLayerFB passthroughLayer_ = XR_NULL_HANDLE;
  PFN_xrCreatePassthroughFB xrCreatePassthroughFB_ = nullptr;
  PFN_xrDestroyPassthroughFB xrDestroyPassthroughFB_ = nullptr;
  PFN_xrCreatePassthroughLayerFB xrCreatePassthroughLayerFB_ = nullptr;
  PFN_xrDestroyPassthroughLayerFB xrDestroyPassthroughLayerFB_ = nullptr;

  // Scene
  CrtScene scene_;
  GLuint videoTexture_ = 0;
  float texMatrix_[16] = {};
  Pose crtPose_;
  float crtScale_ = 1.0f;
  bool crtPlaced_ = false;
  bool haveHead_ = false;
  Pose head_;
  XrTime lastTime_ = 0;

  bool initEgl();
  bool initInstance(JNIEnv* env, jobject activity);
  bool initSession();
  bool initSwapchains();
  bool initActions();
  void initPassthrough();
  void shutdown();

  void pollEvents(bool& quit);
  void frame();
  void updateInput(XrTime time, float dt);
  void updateHand(int h, float dt);
  void placeInFront();
  void haptic(int h, float amplitude);
  void renderEye(int eye, const XrView& view, uint32_t imageIndex);
};

bool XrApp::initEgl() {
  display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (!eglInitialize(display_, nullptr, nullptr)) {
    LOGE("eglInitialize failed");
    return false;
  }
  const EGLint configAttribs[] = {EGL_RED_SIZE,        8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                                  EGL_ALPHA_SIZE,      8, EGL_DEPTH_SIZE, 0, EGL_RENDERABLE_TYPE,
                                  EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                                  EGL_NONE};
  EGLint count = 0;
  if (!eglChooseConfig(display_, configAttribs, &config_, 1, &count) || count == 0) {
    LOGE("eglChooseConfig failed");
    return false;
  }
  const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
  context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, contextAttribs);
  if (context_ == EGL_NO_CONTEXT) {
    LOGE("eglCreateContext failed");
    return false;
  }
  const EGLint pbufferAttribs[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
  pbuffer_ = eglCreatePbufferSurface(display_, config_, pbufferAttribs);
  if (!eglMakeCurrent(display_, pbuffer_, pbuffer_, context_)) {
    LOGE("eglMakeCurrent failed");
    return false;
  }

  const char* ext = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
  if (ext && strstr(ext, "GL_EXT_multisampled_render_to_texture")) {
    glFramebufferTexture2DMultisampleEXT_ =
        reinterpret_cast<PFNGLFRAMEBUFFERTEXTURE2DMULTISAMPLEEXTPROC>(
            eglGetProcAddress("glFramebufferTexture2DMultisampleEXT"));
    glRenderbufferStorageMultisampleEXT_ =
        reinterpret_cast<PFNGLRENDERBUFFERSTORAGEMULTISAMPLEEXTPROC>(
            eglGetProcAddress("glRenderbufferStorageMultisampleEXT"));
    msaa_ = glFramebufferTexture2DMultisampleEXT_ && glRenderbufferStorageMultisampleEXT_;
  }
  LOGI("EGL ready, MSAA %s", msaa_ ? "on" : "off");
  return true;
}

bool XrApp::initInstance(JNIEnv* env, jobject activity) {
  JavaVM* vm = nullptr;
  env->GetJavaVM(&vm);

  PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
  XR_CHECK(xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR",
                                 reinterpret_cast<PFN_xrVoidFunction*>(&initializeLoader)));
  XrLoaderInitInfoAndroidKHR loaderInfo{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
  loaderInfo.applicationVM = vm;
  loaderInfo.applicationContext = activity;
  XR_CHECK(initializeLoader(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR*>(&loaderInfo)));

  uint32_t extCount = 0;
  XR_CHECK(xrEnumerateInstanceExtensionProperties(nullptr, 0, &extCount, nullptr));
  std::vector<XrExtensionProperties> available(extCount, {XR_TYPE_EXTENSION_PROPERTIES});
  XR_CHECK(xrEnumerateInstanceExtensionProperties(nullptr, extCount, &extCount, available.data()));
  auto has = [&](const char* name) {
    return std::any_of(available.begin(), available.end(),
                       [&](const XrExtensionProperties& p) { return !strcmp(p.extensionName, name); });
  };

  std::vector<const char*> extensions = {XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
                                         XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME};
  for (const char* e : extensions) {
    if (!has(e)) {
      LOGE("required extension %s missing", e);
      return false;
    }
  }
  passthroughSupported_ = has(XR_FB_PASSTHROUGH_EXTENSION_NAME);
  if (passthroughSupported_) extensions.push_back(XR_FB_PASSTHROUGH_EXTENSION_NAME);

  XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
  androidInfo.applicationVM = vm;
  androidInfo.applicationActivity = activity;

  XrInstanceCreateInfo createInfo{XR_TYPE_INSTANCE_CREATE_INFO};
  createInfo.next = &androidInfo;
  strncpy(createInfo.applicationInfo.applicationName, "mpvEx", XR_MAX_APPLICATION_NAME_SIZE - 1);
  strncpy(createInfo.applicationInfo.engineName, "mpvEx", XR_MAX_ENGINE_NAME_SIZE - 1);
  createInfo.applicationInfo.applicationVersion = 1;
  createInfo.applicationInfo.apiVersion = XR_API_VERSION_1_0;
  createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
  createInfo.enabledExtensionNames = extensions.data();
  XR_CHECK(xrCreateInstance(&createInfo, &instance_));

  XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};
  systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
  XR_CHECK(xrGetSystem(instance_, &systemInfo, &system_));

  PFN_xrGetOpenGLESGraphicsRequirementsKHR getRequirements = nullptr;
  XR_CHECK(xrGetInstanceProcAddr(instance_, "xrGetOpenGLESGraphicsRequirementsKHR",
                                 reinterpret_cast<PFN_xrVoidFunction*>(&getRequirements)));
  XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
  XR_CHECK(getRequirements(instance_, system_, &requirements));

  if (passthroughSupported_) {
    auto load = [&](const char* name, auto& fn) {
      xrGetInstanceProcAddr(instance_, name, reinterpret_cast<PFN_xrVoidFunction*>(&fn));
    };
    load("xrCreatePassthroughFB", xrCreatePassthroughFB_);
    load("xrDestroyPassthroughFB", xrDestroyPassthroughFB_);
    load("xrCreatePassthroughLayerFB", xrCreatePassthroughLayerFB_);
    load("xrDestroyPassthroughLayerFB", xrDestroyPassthroughLayerFB_);
    passthroughSupported_ = xrCreatePassthroughFB_ && xrDestroyPassthroughFB_ &&
                            xrCreatePassthroughLayerFB_ && xrDestroyPassthroughLayerFB_;
  }
  LOGI("OpenXR instance ready, passthrough %s", passthroughSupported_ ? "available" : "missing");
  return true;
}

bool XrApp::initSession() {
  XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
  binding.display = display_;
  binding.config = config_;
  binding.context = context_;
  XrSessionCreateInfo createInfo{XR_TYPE_SESSION_CREATE_INFO};
  createInfo.next = &binding;
  createInfo.systemId = system_;
  XR_CHECK(xrCreateSession(instance_, &createInfo, &session_));

  uint32_t spaceCount = 0;
  XR_CHECK(xrEnumerateReferenceSpaces(session_, 0, &spaceCount, nullptr));
  std::vector<XrReferenceSpaceType> spaces(spaceCount);
  XR_CHECK(xrEnumerateReferenceSpaces(session_, spaceCount, &spaceCount, spaces.data()));
  stageSpace_ = std::find(spaces.begin(), spaces.end(), XR_REFERENCE_SPACE_TYPE_STAGE) != spaces.end();

  XrReferenceSpaceCreateInfo spaceInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
  spaceInfo.poseInReferenceSpace.orientation.w = 1.0f;
  spaceInfo.referenceSpaceType = stageSpace_ ? XR_REFERENCE_SPACE_TYPE_STAGE : XR_REFERENCE_SPACE_TYPE_LOCAL;
  XR_CHECK(xrCreateReferenceSpace(session_, &spaceInfo, &appSpace_));
  spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
  XR_CHECK(xrCreateReferenceSpace(session_, &spaceInfo, &viewSpace_));
  return true;
}

bool XrApp::initSwapchains() {
  uint32_t viewCount = 0;
  XR_CHECK(xrEnumerateViewConfigurationViews(instance_, system_,
                                             XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0,
                                             &viewCount, nullptr));
  std::vector<XrViewConfigurationView> views(viewCount, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
  XR_CHECK(xrEnumerateViewConfigurationViews(instance_, system_,
                                             XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, viewCount,
                                             &viewCount, views.data()));
  if (viewCount != 2) {
    LOGE("expected 2 views, got %u", viewCount);
    return false;
  }

  uint32_t formatCount = 0;
  XR_CHECK(xrEnumerateSwapchainFormats(session_, 0, &formatCount, nullptr));
  std::vector<int64_t> formats(formatCount);
  XR_CHECK(xrEnumerateSwapchainFormats(session_, formatCount, &formatCount, formats.data()));
  // sRGB lets the compositor treat our output correctly; the shaders work in linear space.
  colorFormat_ = std::find(formats.begin(), formats.end(), GL_SRGB8_ALPHA8) != formats.end()
                     ? GL_SRGB8_ALPHA8
                     : GL_RGBA8;

  for (int i = 0; i < 2; ++i) {
    EyeTarget& eye = eyes_[i];
    eye.width = static_cast<int32_t>(views[i].recommendedImageRectWidth);
    eye.height = static_cast<int32_t>(views[i].recommendedImageRectHeight);

    XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
    info.format = colorFormat_;
    info.sampleCount = 1;
    info.width = eye.width;
    info.height = eye.height;
    info.faceCount = 1;
    info.arraySize = 1;
    info.mipCount = 1;
    XR_CHECK(xrCreateSwapchain(session_, &info, &eye.swapchain));

    uint32_t imageCount = 0;
    XR_CHECK(xrEnumerateSwapchainImages(eye.swapchain, 0, &imageCount, nullptr));
    eye.images.assign(imageCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
    XR_CHECK(xrEnumerateSwapchainImages(
        eye.swapchain, imageCount, &imageCount,
        reinterpret_cast<XrSwapchainImageBaseHeader*>(eye.images.data())));

    glGenRenderbuffers(1, &eye.depth);
    glBindRenderbuffer(GL_RENDERBUFFER, eye.depth);
    if (msaa_) {
      glRenderbufferStorageMultisampleEXT_(GL_RENDERBUFFER, 4, GL_DEPTH_COMPONENT24, eye.width,
                                           eye.height);
    } else {
      glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, eye.width, eye.height);
    }
    glGenFramebuffers(1, &eye.fbo);
  }
  glBindRenderbuffer(GL_RENDERBUFFER, 0);
  LOGI("swapchains %dx%d format 0x%llx", eyes_[0].width, eyes_[0].height,
       static_cast<long long>(colorFormat_));
  return true;
}

bool XrApp::initActions() {
  XrActionSetCreateInfo setInfo{XR_TYPE_ACTION_SET_CREATE_INFO};
  strcpy(setInfo.actionSetName, "player");
  strcpy(setInfo.localizedActionSetName, "Player");
  XR_CHECK(xrCreateActionSet(instance_, &setInfo, &actionSet_));

  XR_CHECK(xrStringToPath(instance_, "/user/hand/left", &hands_[kLeft].path));
  XR_CHECK(xrStringToPath(instance_, "/user/hand/right", &hands_[kRight].path));
  XrPath handPaths[2] = {hands_[kLeft].path, hands_[kRight].path};

  auto create = [&](XrAction& action, XrActionType type, const char* name, const char* label) {
    XrActionCreateInfo info{XR_TYPE_ACTION_CREATE_INFO};
    info.actionType = type;
    strcpy(info.actionName, name);
    strcpy(info.localizedActionName, label);
    info.countSubactionPaths = 2;
    info.subactionPaths = handPaths;
    return xrCreateAction(actionSet_, &info, &action);
  };
  XR_CHECK(create(aimAction_, XR_ACTION_TYPE_POSE_INPUT, "aim", "Aim"));
  XR_CHECK(create(squeezeAction_, XR_ACTION_TYPE_FLOAT_INPUT, "grab", "Grab"));
  XR_CHECK(create(triggerAction_, XR_ACTION_TYPE_FLOAT_INPUT, "select", "Select"));
  XR_CHECK(create(stickAction_, XR_ACTION_TYPE_VECTOR2F_INPUT, "thumbstick", "Thumbstick"));
  XR_CHECK(create(stickClickAction_, XR_ACTION_TYPE_BOOLEAN_INPUT, "thumbstick_click", "Thumbstick click"));
  XR_CHECK(create(primaryAction_, XR_ACTION_TYPE_BOOLEAN_INPUT, "primary", "Primary button"));
  XR_CHECK(create(secondaryAction_, XR_ACTION_TYPE_BOOLEAN_INPUT, "secondary", "Secondary button"));
  XR_CHECK(create(menuAction_, XR_ACTION_TYPE_BOOLEAN_INPUT, "menu", "Menu"));
  XR_CHECK(create(hapticAction_, XR_ACTION_TYPE_VIBRATION_OUTPUT, "haptic", "Haptic"));

  std::vector<XrActionSuggestedBinding> bindings;
  auto bind = [&](XrAction action, const char* path) {
    XrPath p;
    if (XR_SUCCEEDED(xrStringToPath(instance_, path, &p))) bindings.push_back({action, p});
  };
  for (const char* side : {"left", "right"}) {
    std::string base = std::string("/user/hand/") + side;
    bind(aimAction_, (base + "/input/aim/pose").c_str());
    bind(squeezeAction_, (base + "/input/squeeze/value").c_str());
    bind(triggerAction_, (base + "/input/trigger/value").c_str());
    bind(stickAction_, (base + "/input/thumbstick").c_str());
    bind(stickClickAction_, (base + "/input/thumbstick/click").c_str());
    bind(hapticAction_, (base + "/output/haptic").c_str());
  }
  bind(primaryAction_, "/user/hand/left/input/x/click");
  bind(primaryAction_, "/user/hand/right/input/a/click");
  bind(secondaryAction_, "/user/hand/left/input/y/click");
  bind(secondaryAction_, "/user/hand/right/input/b/click");
  bind(menuAction_, "/user/hand/left/input/menu/click");

  XrPath profile;
  XR_CHECK(xrStringToPath(instance_, "/interaction_profiles/oculus/touch_controller", &profile));
  XrInteractionProfileSuggestedBinding suggested{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
  suggested.interactionProfile = profile;
  suggested.countSuggestedBindings = static_cast<uint32_t>(bindings.size());
  suggested.suggestedBindings = bindings.data();
  XR_CHECK(xrSuggestInteractionProfileBindings(instance_, &suggested));

  for (Hand& hand : hands_) {
    XrActionSpaceCreateInfo spaceInfo{XR_TYPE_ACTION_SPACE_CREATE_INFO};
    spaceInfo.action = aimAction_;
    spaceInfo.subactionPath = hand.path;
    spaceInfo.poseInActionSpace.orientation.w = 1.0f;
    XR_CHECK(xrCreateActionSpace(session_, &spaceInfo, &hand.aimSpace));
  }

  XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
  attach.countActionSets = 1;
  attach.actionSets = &actionSet_;
  XR_CHECK(xrAttachSessionActionSets(session_, &attach));
  return true;
}

void XrApp::initPassthrough() {
  if (!passthroughSupported_) return;
  XrPassthroughCreateInfoFB info{XR_TYPE_PASSTHROUGH_CREATE_INFO_FB};
  info.flags = XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB;
  if (XR_FAILED(xrCreatePassthroughFB_(session_, &info, &passthrough_))) {
    LOGE("xrCreatePassthroughFB failed");
    passthroughSupported_ = false;
    return;
  }
  XrPassthroughLayerCreateInfoFB layerInfo{XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB};
  layerInfo.passthrough = passthrough_;
  layerInfo.flags = XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB;
  layerInfo.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
  if (XR_FAILED(xrCreatePassthroughLayerFB_(session_, &layerInfo, &passthroughLayer_))) {
    LOGE("xrCreatePassthroughLayerFB failed");
    xrDestroyPassthroughFB_(passthrough_);
    passthrough_ = XR_NULL_HANDLE;
    passthroughSupported_ = false;
  }
}

void XrApp::shutdown() {
  for (EyeTarget& eye : eyes_) {
    if (eye.fbo) glDeleteFramebuffers(1, &eye.fbo);
    if (eye.depth) glDeleteRenderbuffers(1, &eye.depth);
    if (eye.swapchain) xrDestroySwapchain(eye.swapchain);
    eye = {};
  }
  if (passthroughLayer_) xrDestroyPassthroughLayerFB_(passthroughLayer_);
  if (passthrough_) xrDestroyPassthroughFB_(passthrough_);
  for (Hand& hand : hands_) {
    if (hand.aimSpace) xrDestroySpace(hand.aimSpace);
  }
  if (actionSet_) xrDestroyActionSet(actionSet_);
  if (viewSpace_) xrDestroySpace(viewSpace_);
  if (appSpace_) xrDestroySpace(appSpace_);
  if (session_) xrDestroySession(session_);
  if (instance_) xrDestroyInstance(instance_);

  scene_.destroy();
  if (videoTexture_) glDeleteTextures(1, &videoTexture_);

  if (display_ != EGL_NO_DISPLAY) {
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (pbuffer_ != EGL_NO_SURFACE) eglDestroySurface(display_, pbuffer_);
    if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
    eglTerminate(display_);
  }
}

void XrApp::pollEvents(bool& quit) {
  XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
  while (xrPollEvent(instance_, &event) == XR_SUCCESS) {
    switch (event.type) {
      case XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING:
        quit = true;
        break;
      case XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED: {
        auto* e = reinterpret_cast<XrEventDataSessionStateChanged*>(&event);
        state_ = e->state;
        LOGI("session state %d", state_);
        if (state_ == XR_SESSION_STATE_READY) {
          XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};
          begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
          if (XR_SUCCEEDED(xrBeginSession(session_, &begin))) running_ = true;
        } else if (state_ == XR_SESSION_STATE_STOPPING) {
          xrEndSession(session_);
          running_ = false;
          bridge_.action(kPause);
        } else if (state_ == XR_SESSION_STATE_EXITING || state_ == XR_SESSION_STATE_LOSS_PENDING) {
          quit = true;
        }
        break;
      }
      default:
        break;
    }
    event = {XR_TYPE_EVENT_DATA_BUFFER};
  }
}

void XrApp::haptic(int h, float amplitude) {
  XrHapticVibration vibration{XR_TYPE_HAPTIC_VIBRATION};
  vibration.duration = 20'000'000;  // 20 ms
  vibration.frequency = XR_FREQUENCY_UNSPECIFIED;
  vibration.amplitude = amplitude;
  XrHapticActionInfo info{XR_TYPE_HAPTIC_ACTION_INFO};
  info.action = hapticAction_;
  info.subactionPath = hands_[h].path;
  xrApplyHapticFeedback(session_, &info, reinterpret_cast<XrHapticBaseHeader*>(&vibration));
}

void XrApp::placeInFront() {
  if (!haveHead_) return;
  float yaw = yawOf(head_.q);
  Vec3 forward{-std::sin(yaw), 0, -std::cos(yaw)};
  crtPose_.p = head_.p + forward * 1.4f;
  crtPose_.p.y = head_.p.y - 0.05f;
  // The screen faces +Z, so the same yaw as the head points it back at the viewer.
  crtPose_.q = axisAngle({0, 1, 0}, yaw);
  crtPlaced_ = true;
  bridge_.crtPoseChanged(crtPose_, crtScale_);
}

void XrApp::updateInput(XrTime time, float dt) {
  XrSpaceLocation headLocation{XR_TYPE_SPACE_LOCATION};
  if (XR_SUCCEEDED(xrLocateSpace(viewSpace_, appSpace_, time, &headLocation)) &&
      (headLocation.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) &&
      (headLocation.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) {
    head_ = fromXr(headLocation.pose);
    haveHead_ = true;
  }
  if (!crtPlaced_) placeInFront();

  bool focused = state_ == XR_SESSION_STATE_FOCUSED;
  if (focused) {
    XrActiveActionSet active{actionSet_, XR_NULL_PATH};
    XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
    sync.countActiveActionSets = 1;
    sync.activeActionSets = &active;
    focused = XR_SUCCEEDED(xrSyncActions(session_, &sync));
  }

  for (int h = 0; h < 2; ++h) {
    Hand& hand = hands_[h];
    hand.active = false;
    if (!focused) continue;

    XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO};
    get.subactionPath = hand.path;

    get.action = aimAction_;
    XrActionStatePose poseState{XR_TYPE_ACTION_STATE_POSE};
    xrGetActionStatePose(session_, &get, &poseState);
    XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
    if (poseState.isActive && XR_SUCCEEDED(xrLocateSpace(hand.aimSpace, appSpace_, time, &location)) &&
        (location.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) &&
        (location.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) {
      hand.aim = fromXr(location.pose);
      hand.active = true;
    }

    auto getFloat = [&](XrAction action) {
      get.action = action;
      XrActionStateFloat s{XR_TYPE_ACTION_STATE_FLOAT};
      xrGetActionStateFloat(session_, &get, &s);
      return s.isActive ? s.currentState : 0.0f;
    };
    auto pressed = [&](XrAction action) {
      get.action = action;
      XrActionStateBoolean s{XR_TYPE_ACTION_STATE_BOOLEAN};
      xrGetActionStateBoolean(session_, &get, &s);
      return s.isActive && s.changedSinceLastSync && s.currentState;
    };
    hand.squeeze = getFloat(squeezeAction_);
    hand.trigger = getFloat(triggerAction_);
    get.action = stickAction_;
    XrActionStateVector2f stick{XR_TYPE_ACTION_STATE_VECTOR2F};
    xrGetActionStateVector2f(session_, &get, &stick);
    hand.stick = stick.isActive ? stick.currentState : XrVector2f{0, 0};
    hand.stickClickPressed = pressed(stickClickAction_);
    hand.primaryPressed = pressed(primaryAction_);
    hand.secondaryPressed = pressed(secondaryAction_);
    hand.menuPressed = pressed(menuAction_);
  }

  for (int h = 0; h < 2; ++h) updateHand(h, dt);
}

void XrApp::updateHand(int h, float dt) {
  Hand& hand = hands_[h];
  Hand& other = hands_[1 - h];
  hand.hoverButton = -1;
  if (!hand.active) {
    hand.pressedButton = -1;
    if (hand.grabbing) {
      hand.grabbing = false;
      bridge_.crtPoseChanged(crtPose_, crtScale_);
    }
    return;
  }

  Vec3 origin = hand.aim.p;
  Vec3 dir = rotate(hand.aim.q, {0, 0, -1});
  float hit = scene_.rayHit(crtPose_, crtScale_, origin, dir);
  bool pointing = hit >= 0.0f && hit < 20.0f;

  // Grab start / end with hysteresis.
  if (!hand.grabbing && !other.grabbing && pointing && hand.squeeze > 0.6f) {
    hand.grabbing = true;
    hand.pressedButton = -1;
    hand.grabOffset = inverse(hand.aim) * crtPose_;
    hand.grabYaw = 0;
    haptic(h, 0.5f);
  } else if (hand.grabbing && hand.squeeze < 0.4f) {
    hand.grabbing = false;
    haptic(h, 0.25f);
    bridge_.crtPoseChanged(crtPose_, crtScale_);
  }

  if (hand.grabbing) {
    // Thumbstick Y pushes the set away or pulls it in along the ray; X spins it.
    // The controller looks down -Z, so a more negative offset is further away.
    if (std::fabs(hand.stick.y) > 0.15f) {
      hand.grabOffset.p.z = std::min(hand.grabOffset.p.z - hand.stick.y * dt * 1.5f, -0.3f);
    }
    if (std::fabs(hand.stick.x) > 0.15f) hand.grabYaw -= hand.stick.x * dt * 2.0f;
    if (hand.stickClickPressed) {
      const float modelInches = scene_.screenDiagonalInches();
      float inches = crtScale_ * modelInches;
      float next = kSizePresetsInches[0];
      for (float preset : kSizePresetsInches) {
        if (preset > inches + 0.5f) {
          next = preset;
          break;
        }
      }
      crtScale_ = next / modelInches;
      haptic(h, 0.35f);
    }
    Pose held = hand.aim * hand.grabOffset;
    // Keep the set upright; only its heading follows the controller.
    crtPose_.p = held.p;
    crtPose_.q = axisAngle({0, 1, 0}, yawOf(held.q) + hand.grabYaw);
    return;
  }

  // Trigger presses front-panel buttons; anywhere else on the set toggles playback.
  if (pointing) hand.hoverButton = scene_.buttonAt(crtPose_, crtScale_, origin, dir);
  bool triggerDown = hand.trigger > 0.7f || (hand.triggerDown && hand.trigger > 0.5f);
  if (triggerDown && !hand.triggerDown) {
    if (hand.hoverButton >= 0) {
      hand.pressedButton = hand.hoverButton;
      hand.buttonRepeat = 0.45f;
      haptic(h, 0.3f);
      bridge_.action(scene_.button(hand.pressedButton).action);
    } else if (pointing) {
      bridge_.action(kTogglePause);
    }
  } else if (triggerDown && hand.pressedButton >= 0) {
    // Holding rewind / fast-forward / volume keeps going while still on the button.
    const CrtButton& held = scene_.button(hand.pressedButton);
    if (held.repeats && hand.hoverButton == hand.pressedButton) {
      hand.buttonRepeat -= dt;
      if (hand.buttonRepeat <= 0) {
        hand.buttonRepeat = 0.3f;
        bridge_.action(held.action);
      }
    }
  } else if (!triggerDown) {
    hand.pressedButton = -1;
  }
  hand.triggerDown = triggerDown;

  if (hand.primaryPressed) bridge_.action(h == kRight ? kTogglePause : kCycleAudio);
  if (hand.secondaryPressed) bridge_.action(h == kRight ? kShowProgress : kCycleSubtitles);
  if (hand.menuPressed) bridge_.action(kExit);
  if (hand.stickClickPressed) placeInFront();

  // Thumbstick flicks repeat while held. X seeks 10 s on either hand; Y seeks 5 min on the right
  // hand and changes volume on the left.
  int dirNow = 0;
  const XrVector2f s = hand.stick;
  if (std::fabs(s.x) > 0.7f && std::fabs(s.x) >= std::fabs(s.y)) dirNow = s.x > 0 ? 1 : 2;
  else if (std::fabs(s.y) > 0.7f) dirNow = s.y > 0 ? 3 : 4;
  bool released = std::fabs(s.x) < 0.3f && std::fabs(s.y) < 0.3f;

  auto fire = [&](int d) {
    static const jint rightCodes[] = {0, kSeekForward, kSeekBack, kSeekForwardLong, kSeekBackLong};
    static const jint leftCodes[] = {0, kSeekForward, kSeekBack, kVolumeUp, kVolumeDown};
    bridge_.action(h == kRight ? rightCodes[d] : leftCodes[d]);
  };
  if (hand.stickDir == 0 && dirNow != 0) {
    hand.stickDir = dirNow;
    hand.stickRepeat = 0.45f;
    fire(dirNow);
  } else if (hand.stickDir != 0) {
    if (released) {
      hand.stickDir = 0;
    } else if (dirNow == hand.stickDir) {
      hand.stickRepeat -= dt;
      if (hand.stickRepeat <= 0) {
        hand.stickRepeat = 0.3f;
        fire(dirNow);
      }
    }
  }
}

void XrApp::renderEye(int eyeIndex, const XrView& view, uint32_t imageIndex) {
  EyeTarget& eye = eyes_[eyeIndex];
  GLuint color = eye.images[imageIndex].image;

  glBindFramebuffer(GL_FRAMEBUFFER, eye.fbo);
  if (msaa_) {
    glFramebufferTexture2DMultisampleEXT_(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                                          color, 0, 4);
  } else {
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
  }
  glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, eye.depth);

  glViewport(0, 0, eye.width, eye.height);
  glEnable(GL_DEPTH_TEST);
  glDepthFunc(GL_LEQUAL);
  glDisable(GL_CULL_FACE);
  glDisable(GL_BLEND);
  // Transparent where nothing is drawn so passthrough shows through.
  glClearColor(0, 0, 0, passthroughLayer_ ? 0.0f : 1.0f);
  glClearDepthf(1.0f);
  glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

  Pose eyePose = fromXr(view.pose);
  Mat4 viewProj = Mat4::projection(view.fov, 0.05f, 100.0f) * Mat4::fromPose(inverse(eyePose));

  ControllerVisual visuals[2];
  for (int h = 0; h < 2; ++h) {
    const Hand& hand = hands_[h];
    visuals[h].active = hand.active;
    visuals[h].aim = hand.aim;
    if (hand.active) {
      Vec3 dir = rotate(hand.aim.q, {0, 0, -1});
      float hit = scene_.rayHit(crtPose_, crtScale_, hand.aim.p, dir);
      visuals[h].highlighted = hand.grabbing || (hit >= 0 && hit < 20.0f);
      visuals[h].rayLength = visuals[h].highlighted && hit >= 0 ? hit : 0.6f;
    }
  }

  scene_.draw(viewProj, eyePose.p, crtPose_, crtScale_, visuals, 2);

  const GLenum discard = GL_DEPTH_ATTACHMENT;
  glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, &discard);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

void XrApp::frame() {
  XrFrameWaitInfo waitInfo{XR_TYPE_FRAME_WAIT_INFO};
  XrFrameState frameState{XR_TYPE_FRAME_STATE};
  if (XR_FAILED(xrWaitFrame(session_, &waitInfo, &frameState))) return;
  XrFrameBeginInfo beginInfo{XR_TYPE_FRAME_BEGIN_INFO};
  if (XR_FAILED(xrBeginFrame(session_, &beginInfo))) return;

  XrTime now = frameState.predictedDisplayTime;
  float dt = lastTime_ ? std::clamp(static_cast<float>(now - lastTime_) * 1e-9f, 0.0f, 0.1f) : 0.0f;
  lastTime_ = now;

  updateInput(now, dt);

  bool hasFrame = bridge_.updateVideo(texMatrix_);
  scene_.setVideo(videoTexture_, texMatrix_, hasFrame);
  scene_.setPlaying(bridge_.playing());
  uint32_t hovered = 0, pressed = 0;
  for (const Hand& hand : hands_) {
    if (hand.hoverButton >= 0) hovered |= 1u << hand.hoverButton;
    if (hand.pressedButton >= 0) pressed |= 1u << hand.pressedButton;
  }
  scene_.setButtonState(hovered, pressed);

  std::vector<XrCompositionLayerBaseHeader*> layers;
  XrCompositionLayerPassthroughFB passthroughLayer{XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB};
  XrCompositionLayerProjection projection{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
  XrCompositionLayerProjectionView projectionViews[2] = {{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW},
                                                         {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};

  if (passthroughLayer_) {
    passthroughLayer.layerHandle = passthroughLayer_;
    passthroughLayer.flags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
    layers.push_back(reinterpret_cast<XrCompositionLayerBaseHeader*>(&passthroughLayer));
  }

  if (frameState.shouldRender && crtPlaced_) {
    XrViewLocateInfo locateInfo{XR_TYPE_VIEW_LOCATE_INFO};
    locateInfo.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    locateInfo.displayTime = now;
    locateInfo.space = appSpace_;
    XrViewState viewState{XR_TYPE_VIEW_STATE};
    XrView views[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
    uint32_t viewCount = 0;
    bool located =
        XR_SUCCEEDED(xrLocateViews(session_, &locateInfo, &viewState, 2, &viewCount, views)) &&
        (viewState.viewStateFlags & XR_VIEW_STATE_POSITION_VALID_BIT) &&
        (viewState.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT);

    if (located) {
      for (int i = 0; i < 2; ++i) {
        EyeTarget& eye = eyes_[i];
        uint32_t imageIndex = 0;
        XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
        xrAcquireSwapchainImage(eye.swapchain, &acquire, &imageIndex);
        XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
        wait.timeout = XR_INFINITE_DURATION;
        xrWaitSwapchainImage(eye.swapchain, &wait);

        renderEye(i, views[i], imageIndex);

        XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        xrReleaseSwapchainImage(eye.swapchain, &release);

        projectionViews[i].pose = views[i].pose;
        projectionViews[i].fov = views[i].fov;
        projectionViews[i].subImage.swapchain = eye.swapchain;
        projectionViews[i].subImage.imageRect = {{0, 0}, {eye.width, eye.height}};
      }
      projection.space = appSpace_;
      projection.viewCount = 2;
      projection.views = projectionViews;
      if (passthroughLayer_) projection.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
      layers.push_back(reinterpret_cast<XrCompositionLayerBaseHeader*>(&projection));
    }
  }

  XrFrameEndInfo endInfo{XR_TYPE_FRAME_END_INFO};
  endInfo.displayTime = now;
  endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
  endInfo.layerCount = static_cast<uint32_t>(layers.size());
  endInfo.layers = layers.data();
  xrEndFrame(session_, &endInfo);
}

bool XrApp::run(JNIEnv* env, jobject activity, jobject bridge, jfloatArray initialPose,
                const char* modelPath) {
  if (!bridge_.init(env, bridge)) return false;

  bool ok = initInstance(env, activity) && initEgl() && initSession() && initSwapchains() &&
            initActions() && scene_.init(modelPath);
  if (!ok) {
    LOGE("XR initialisation failed");
    shutdown();
    bridge_.release();
    return false;
  }
  initPassthrough();

  if (initialPose && env->GetArrayLength(initialPose) == 8 && stageSpace_) {
    float v[8];
    env->GetFloatArrayRegion(initialPose, 0, 8, v);
    crtPose_ = {normalize(Quat{v[3], v[4], v[5], v[6]}), {v[0], v[1], v[2]}};
    crtScale_ = std::clamp(v[7], 0.3f, 3.0f);
    crtPlaced_ = true;
  }

  glGenTextures(1, &videoTexture_);
  glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTexture_);
  glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
  // Kotlin wraps the texture in a SurfaceTexture and hands its Surface to mpv.
  bridge_.glReady(videoTexture_, scene_.screenAspect());

  bool quit = false;
  while (!quit) {
    pollEvents(quit);
    if (quit) break;

    if (gExitRequested.load() && !exitRequestSent_) {
      exitRequestSent_ = true;
      if (running_) {
        xrRequestExitSession(session_);
      } else {
        break;
      }
    }

    if (!running_) {
      // Idle until the runtime moves us to READY again (or we're told to stop).
      struct timespec ts{0, 10'000'000};
      nanosleep(&ts, nullptr);
      continue;
    }
    frame();
  }

  // Kotlin must detach mpv and release the SurfaceTexture while our context is still current.
  bridge_.sessionEnded();
  shutdown();
  bridge_.release();
  return true;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_app_marlboroadvance_mpvex_ui_player_xr_XrNative_run(JNIEnv* env, jclass, jobject activity,
                                                         jobject bridge, jfloatArray initialPose,
                                                         jstring modelPath) {
  gExitRequested.store(false);
  std::string path;
  if (modelPath) {
    const char* chars = env->GetStringUTFChars(modelPath, nullptr);
    path = chars;
    env->ReleaseStringUTFChars(modelPath, chars);
  }
  XrApp app;
  return app.run(env, activity, bridge, initialPose, path.c_str()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_marlboroadvance_mpvex_ui_player_xr_XrNative_requestExit(JNIEnv*, jclass) {
  gExitRequested.store(true);
}

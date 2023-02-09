# And-OneShotCamera
単射専用カメラのサンプル<br/>

Androidサンプルの[Camera2Basic](https://github.com/googlearchive/android-Camera2Basic.git)と内容はほぼ同じ。<br/>
ビルドが通らんかったから、再作成した。

αバージョン ... まだ、開発中。

# And-OneShotCamera
One shot only camera sample code.<br/>


The content is almost the same as the Android sample [Camera2Basic](https://github.com/googlearchive/android-Camera2Basic.git). <br/>
The build didn't work, so I recreated it.


Alpha version ... still under development.


# Camera2 初期化 シーケンス
# Initialization flow of Camera2

## 1st 権限取得シーケンス
## 1st Ask for permission
```mermaid
sequenceDiagram
system ->> MainActivity: onCreate()
MainActivity ->> MainActivity: registerForActivityResult()
Note over MainActivity : If permission denied, exit this application.
MainActivity ->> MainFragment: newInstance()
```

## 2nd Fragment::onResume()シーケンス
## 2nd Sequence of Fragment::onResume()
```mermaid
sequenceDiagram
system ->> MainFragment: onResume()
MainFragment ->> Handler: new() : Handler
MainFragment ->> TextureView: setSurfaceTextureListener()
Note over TextureView: wait in onSurfaceTextureAvailable()<br> onSurfaceTextureSizeChanged()<br> onSurfaceTextureDestroyed()<br> onSurfaceTextureUpdated()
```

## 3rd openCamera()シーケンス
## 3rd Sequence of openCamera()
```mermaid
sequenceDiagram
system ->> TextureView: onSurfaceTextureAvailable()
TextureView ->> MainFragment : openCamera(1920,1080)
MainFragment ->> MainFragment : setUpCameraOutputs(1920,1080) : mCameraId
MainFragment ->> ImageReader: newInstance() : ImageReader
MainFragment ->> ImageReader: setOnImageAvailableListener()
Note over ImageReader: wait in onImageAvailable()
MainFragment ->> MainFragment : configureTransform(1920,1080)
MainFragment ->> Activity: getSystemService(Context.CAMERA_SERVICE) : CameraManager
MainFragment ->> CameraManager: openCamera(mCameraId, mStateCallback, mBackgroundHandler)
Note over CameraManager: set the CameraDevice.StateCallback class<br> wait in onOpened()<br> onDisconnected()<br> onError()
```
## 4th CameraDevice.StateCallback::onOpened()->プレビュー開始シーケンス
## 4th Sequence of CameraDevice.StateCallback::onOpened() -> Preview starting
```mermaid
sequenceDiagram
system ->> CameraDevice.StateCallback: onOpened(CameraDevice mCameraDevice)
CameraDevice.StateCallback ->> MainFragment : createCameraPreviewSession()
Note over MainFragment : Start camera preview
opt ready
	MainFragment ->> TextureView: ogetSurfaceTexture() : SurfaceTexture
	MainFragment ->> SurfaceTexture: setDefaultBufferSize(1920, 1080)
	MainFragment ->> Surface: new(SurfaceTexture) : Surface
	MainFragment ->> CameraDevice: createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) : CaptureRequest.Builder
	MainFragment ->> CaptureRequest.Builder: addTarget(Surface)
end
MainFragment ->> CameraDevice: createCaptureSession([TextureView::Surface, ImageReader::Surface], callback)
Note over CameraDevice: set the CameraCaptureSession.StateCallback class<br> wait in onConfigured()<br> onConfigureFailed()
```

## 5th プレビュー設定完了シーケンス
## 5th Preview setting completion sequence

```mermaid
sequenceDiagram
system ->> CameraCaptureSession.StateCallback: onConfigured(CameraCaptureSession)
CameraCaptureSession.StateCallback ->> CaptureRequest.Builder: set(CONTROL_AF_MODE_CONTINUOUS_PICTURE)
CameraCaptureSession.StateCallback ->> CaptureRequest.Builder: set(CONTROL_AE_MODE_ON_AUTO_FLASH)
CameraCaptureSession.StateCallback ->> CameraCaptureSession: setRepeatingRequest(CaptureRequest.Builder.build(), CameraCaptureSession.CaptureCallback)
Note over CameraCaptureSession.CaptureCallback: set the CameraCaptureSession.StateCallback class<br> wait in onCaptureProgressed()<br> onCaptureCompleted()
```

# Camera2 プレビュー実行 シーケンス
# camera preview flow of Camera2

```mermaid
sequenceDiagram
system ->> CameraCaptureSession.StateCallback: onCaptureCompleted()
CameraCaptureSession.StateCallback ->> CameraCaptureSession.StateCallback: process(CaptureResult)
Note over CameraCaptureSession.StateCallback : We have nothing to do when the camera preview is working normally.
```

作るときに StackEdit – In-browser Markdown editor などを使うと WYSIWYG でいい感じです。
https://qiita.com/takke/items/86a5ddf145cf9693b6e9

markdownでシーケンス図を書こう
https://qiita.com/konitech913/items/90f91687cfe7ece50020

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


# Camera2使用のシーケンス
# Sequence using camera2

```mermaid
sequenceDiagram
system ->> MainActivity: onCreate()
MainActivity ->> MainActivity: registerForActivityResult()
Note over MainActivity : If permission denied, exit here.
MainActivity ->> system: MainFragment::newInstance()
opt onResume() sequence
	system ->> MainFragment: onResume()
	MainFragment ->> Handler: new()
	MainFragment ->> TextureView: setSurfaceTextureListener()
	Note over TextureView: wait in onSurfaceTextureAvailable()<br> onSurfaceTextureSizeChanged()<br> onSurfaceTextureDestroyed()<br> onSurfaceTextureUpdated()
end

opt openCamera() sequence
	system ->> TextureView: onSurfaceTextureAvailable()
	TextureView ->> MainFragment : openCamera()
	MainFragment ->> MainFragment : setUpCameraOutputs()
	MainFragment ->> ImageReader: newInstance()
	MainFragment ->> ImageReader: setOnImageAvailableListener()
	Note over ImageReader: wait in onImageAvailable()
	MainFragment ->> MainFragment : setUpCameraOutputs() : mCameraId
	MainFragment ->> TextureView: chooseOptimalSize() : Size
	MainFragment ->> TextureView: setMatrix()
	MainFragment ->> TextureView: openCamera(mCameraId, mStateCallback, mBackgroundHandler)
	Note over CameraDevice: wait in onOpened()<br> onDisconnected()<br> onError()
end

opt startPreview() sequence
	system ->> CameraDevice: onOpened()
	CameraDevice->> MainFragment : createCameraPreviewSession()
	Note over MainFragment : Start camera preview
end

system ->> MainFragment: onPause()
MainFragment ->> Handler: stop

```

作るときに StackEdit – In-browser Markdown editor などを使うと WYSIWYG でいい感じです。
https://qiita.com/takke/items/86a5ddf145cf9693b6e9

markdownでシーケンス図を書こう
https://qiita.com/konitech913/items/90f91687cfe7ece50020

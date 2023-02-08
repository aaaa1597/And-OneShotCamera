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
Alice ->> Bob: Hello Bob, how are you? aaa
Bob-->>John: How about you John?
Bob--x Alice: I am good thanks!
Bob-x John: I am good thanks!
Note right of John: Bob thinks a long<br/>long time, so long<br/>that the text does<br/>not fit on a row.

Bob-->Alice: Checking with John...
Alice->John: Yes... John, how are you?
```

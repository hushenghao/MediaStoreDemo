# MediaStoreDemo

# 支持Android 12，全版本保存图片到相册方案

## 背景
由于Google对用户隐私和系统安全做的越来越完善，应用对一些敏感信息的操作越来越难。比如最常见的共享存储空间的访问，像保存图片到相册这种常见的需求。

* `Android 6.0` 以前，应用要想保存图片到相册，只需要通过`File`对象打开IO流就可以保存；
* `Android 6.0` 添加了运行时权限，需要先申请存储权限才可以保存图片；
* `Android 10` 引入了分区存储，但不是强制的，可以通过`requestLegacyExternalStorage=true`关闭分区存储；
* `Android 11` 强制开启分区存储，应用以 Android 11 为目标版本，系统会忽略 `requestLegacyExternalStorage`标记，访问共享存储空间都需要使用`MediaStore`进行访问。

我们通过上面的时间线可以看出，Google对系统公共存储的访问的门槛逐渐升高，摒弃传统的Java File对象直接访问文件的方式，想将Android的共享空间访问方式统一成一套API。这是我们的主角`MediaStore`

`MediaStore` 是Android诞生之初就存在的一套媒体库框架，通过[文档](https://developer.android.google.cn/reference/android/provider/MediaStore)可以看到`Added in API level 1`。但是由于最初系统比较开放，我们对它的使用并不多，但是随着分区存储的开启，它的舞台会越来越多。

所以怎么才是正确的保存图片的方案呢？话不多说，步入正题

## 大致流程

我们访问`MediaStore`有点像访问数据库，实际上就是数据库，只是多了一些IO流的操作。将图片想象成数据库中的一条数据，我们怎么插入数据库呢，回想sqlite怎么操作的。 

实际上`Mediastore`也是这样的：
1. 先将图片记录插入媒体库，获得插入的Uri；
2. 然后通过插入Uri打开输出流将文件写入；

大致流程就是这样子，只是不同的版本有一些细微的差距；

* Android 10 之前的版本需要申请存储权限，**Android 10及以后版本是不需要读写权限的**
* Android 10 之前是通过File路径打开流的，所以需要判断文件是否已经存在，否者的话会将以存在的图片给覆盖
* Android 10 及以后版本添加了`IS_PENDING`状态标识，为0时其他应用才可见，所以在图片保存过后需要更新这个标识。

相信说了这么多，大家已经不耐烦了，不慌代码马上就来。

## 编码时间

这里用保存Bitmap到图库为例，保存文件和权限申请的逻辑，这里就不贴代码了，详见[Demo](https://github.com/hushenghao/MediaStoreDemo.git)

```kotlin
// 为了演示方便，生产环境记得在IO线程处理
// decode bitmap
val bitmap = BitmapFactory.decodeStream(assets.open("wallhaven_rdyyjm.jpg"))
// 保存bitmap到相册
val uri = bitmap.saveToAlbum(context, fileName = "save_wallhaven_rdyyjm.jpg")
```

是的很简单，详细实现是怎么弄的，接着往下看。

```kotlin
const val MIME_PNG = "image/png"
const val MIME_JPG = "image/jpg"
// 保存位置，这里使用Picures，也可以改为 DCIM
private val ALBUM_DIR = Environment.DIRECTORY_PICTURES

/**
 * 用于Q以下系统获取图片文件大小来更新[MediaStore.Images.Media.SIZE]
 */
private class OutputFileTaker(var file: File? = null)

/**
 * 保存Bitmap到相册的Pictures文件夹
 *
 * @param context 上下文
 * @param fileName 文件名。 需要携带后缀
 * @param relativePath 相对于Pictures的路径
 * @param quality 质量
 */
fun Bitmap.saveToAlbum(
    context: Context,
    fileName: String,
    relativePath: String? = null,
    quality: Int = 75
): Uri? {    
    val resolver = context.contentResolver
    val outputFile = OutputFileTaker()
    // 插入图片信息
    val imageUri = resolver.insertMediaImage(fileName, relativePath, outputFile)
    if (imageUri == null) {
        Log.w(TAG, "insert: error: uri == null")
        return null
    }

    // 通过Uri打开输出流
    (imageUri.outputStream(resolver) ?: return null).use {
        val format =
            if (fileName.endsWith(".png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        // 保存图片
        this@saveToAlbum.compress(format, quality, it)
        // 更新 IS_PENDING 状态
        imageUri.finishPending(context, resolver, outputFile.file)
    }
    return imageUri
}

private fun Uri.outputStream(resolver: ContentResolver): OutputStream? {
    return try {
        // 通过Uri打开输出流。同理也可以打开输入流，读取媒体库文件
        resolver.openOutputStream(this)
    } catch (e: FileNotFoundException) {
        Log.e(TAG, "save: open stream error: $e")
        null
    }
}

private fun Uri.finishPending(
    context: Context,
    resolver: ContentResolver,
    outputFile: File?
) {
    val imageValues = ContentValues()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        if (outputFile != null) {
            // Android 10 以下需要更新文件大小字段，否则部分设备的图库里照片大小显示为0
            imageValues.put(MediaStore.Images.Media.SIZE, outputFile.length())
        }
        resolver.update(this, imageValues, null, null)
        // 通知媒体库更新，部分设备不更新 图库看不到 ？？？
        val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, this)
        context.sendBroadcast(intent)
    } else {
        // Android Q添加了IS_PENDING状态，为0时其他应用才可见
        imageValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(this, imageValues, null, null)
    }
}

/**
 * 插入图片到媒体库
 */
private fun ContentResolver.insertMediaImage(
    fileName: String,
    relativePath: String?,
    outputFileTaker: OutputFileTaker? = null
): Uri? {
    // 图片信息
    val imageValues = ContentValues().apply {
        val mimeType = if (fileName.endsWith(".png")) MIME_PNG else MIME_JPG
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        // 插入时间
        val date = System.currentTimeMillis() / 1000
        put(MediaStore.Images.Media.DATE_ADDED, date)
        put(MediaStore.Images.Media.DATE_MODIFIED, date)
    }
    // 保存的位置
    val collection: Uri
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val path = if (relativePath != null) "${ALBUM_DIR}/${relativePath}" else ALBUM_DIR
        imageValues.apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // 高版本不用查重直接插入，会自动重命名
    } else {
        // 老版本
        val pictures = Environment.getExternalStoragePublicDirectory(ALBUM_DIR)
        val saveDir = if (relativePath != null) File(pictures, relativePath) else pictures

        if (!saveDir.exists() && !saveDir.mkdirs()) {
            Log.e(TAG, "save: error: can't create Pictures directory")
            return null
        }

        // 文件路径查重，重复的话在文件名后拼接数字
        var imageFile = File(saveDir, fileName)
        val fileNameWithoutExtension = imageFile.nameWithoutExtension
        val fileExtension = imageFile.extension

        // 查询文件是否已经存在
        var queryUri = this.queryMediaImage28(imageFile.absolutePath)
        var suffix = 1
        while (queryUri != null) {
            // 存在的话重命名，路径后面拼接 fileNameWithoutExtension(数字).png
            val newName = fileNameWithoutExtension + "(${suffix++})." + fileExtension
            imageFile = File(saveDir, newName)
            queryUri = this.queryMediaImage28(imageFile.absolutePath)
        }

        imageValues.apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, imageFile.name)
            // 保存路径
            val imagePath = imageFile.absolutePath
            Log.v(TAG, "save file: $imagePath")
            put(MediaStore.Images.Media.DATA, imagePath)
        }
        outputFileTaker?.file = imageFile// 回传文件路径，用于设置文件大小
        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    // 插入图片信息
    return this.insert(collection, imageValues)
}

/**
 * Android Q以下版本，查询媒体库中当前路径是否存在
 * @return Uri 返回null时说明不存在，可以进行图片插入逻辑
 */
private fun ContentResolver.queryMediaImage28(imagePath: String): Uri? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null

    val imageFile = File(imagePath)
    if (imageFile.canRead() && imageFile.exists()) {
        Log.v(TAG, "query: path: $imagePath exists")
        // 文件已存在，返回一个file://xxx的uri
        // 这个逻辑也可以不要，但是为了减少媒体库查询次数，可以直接判断文件是否存在
        return Uri.fromFile(imageFile)
    }
    // 保存的位置
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    // 查询是否已经存在相同图片
    val query = this.query(
        collection,
        arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA),
        "${MediaStore.Images.Media.DATA} == ?",
        arrayOf(imagePath), null
    )
    query?.use {
        while (it.moveToNext()) {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val id = it.getLong(idColumn)
            val existsUri = ContentUris.withAppendedId(collection, id)
            Log.v(TAG, "query: path: $imagePath exists uri: $existsUri")
            return existsUri
        }
    }
    return null
}

private const val TAG = "ImageExt"// Log tag
```
**大家期盼已久的代码** [ImageExt.kt](https://github.com/hushenghao/MediaStoreDemo)

## 图片分享

有很多场景是保存图片之后，调用第三方分享进行图片分享，但是一些文章不管三七二十一说需要用`FileProvider`。实际上这是不准确的，大部分情况是需要，一些场景是不需要的。

我们只需要记得 **FileProvider是给其他应用分享应用私有文件的** 就够了，只有在我们需要将应用沙盒内的文件共享出去的时候才需要配置FileProvider。例如：

* 应用内更新，系统包安装器需要读取系统沙盒内的apk文件（如果你下载了公共路径那另说）
* 应用内沙盒图片分享，微信已经要求一定要通过FileProvider才可以分享图片了（没有适配的赶紧看看分享还能用吗）

但是保存到系统图库并分享的场景明显就不符合这个场景，因为图库不是应用私有的空间。

所以在使用FileProvider要区分一下场景，是不是可以不需要，因为FileProvider是一种特殊的ContentProvider，每一个内容提供者在应用启动的时候都要初始化，所以也会拖慢应用的启动速度。

## 参考资料

[访问共享存储空间中的媒体文件](https://developer.android.google.cn/training/data-storage/shared/media)
[MediaStore](https://developer.android.google.cn/reference/android/provider/MediaStore)
[OpenSDK支持FileProvider方式分享文件到微信](
https://developers.weixin.qq.com/community/develop/doc/0004886026c1a8402d2a040ee5b401)


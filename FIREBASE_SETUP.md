# Hướng Dẫn Cấu Hình Firebase cho Dự Án CaroXO (Project & Firebase Console)

Tài liệu này hướng dẫn chi tiết cách cấu hình Firebase trên **Firebase Console** và trong **Android Studio Project (`CaroXO`)** cho tính năng xác thực tài khoản (Google Sign-In, Email/Password) và lưu trữ đồng bộ thời gian thực (Cloud Firestore + Bảng xếp hạng).

---

## Phần 1: Cấu Hình Trên Firebase Console

### 1.1. Tạo Project & Đăng ký Ứng dụng Android
1. Truy cập [Firebase Console](https://console.firebase.google.com/) → chọn **Add project** (hoặc chọn Project có sẵn).
2. Trong màn hình **Project Overview**, nhấn biểu tượng **Android** để thêm ứng dụng:
   - **Android package name**: `com.game.caroxo.an` *(bắt buộc khớp với `applicationId` trong `app/build.gradle.kts`)*.
   - **App nickname**: `CaroXO`.
   - **Debug signing certificate SHA-1**: dán mã SHA-1 của keystore bạn dùng để build (xem cách lấy SHA-1/SHA-256 ở mục 1.2).
3. Tải file **`google-services.json`** về máy.

### 1.2. Lấy mã SHA-1 và SHA-256 của Keystore
Mở Terminal tại thư mục gốc dự án và chạy:

```bash
keytool -list -v -keystore debug.keystore -alias androiddebugkey -storepass android -keypass android
```

- Copy giá trị **SHA1** và **SHA256**.
- Vào **Firebase Console → Project Settings (biểu tượng bánh răng) → Your apps → CaroXO (`com.game.caroxo.an`) → Add fingerprint** và thêm cả **SHA-1** lẫn **SHA-256**.
> **Lưu ý:** Nếu thiếu SHA-1/SHA-256, Google Sign-In (Credential Manager) sẽ báo lỗi hủy hoặc không tìm thấy cấu hình OAuth.

### 1.3. Bật Các Phương Thức Đăng Nhập (Authentication)
Vào **Build → Authentication → Get started → Sign-in method**:
1. **Google**:
   - Chọn **Google → Enable**.
   - Chọn **Project support email** → **Save**.
   - Khi tải lại `google-services.json` sau bước này, plugin Google Services sẽ tự sinh `default_web_client_id` trong Android Project.
2. **Email/Password** *(bật thủ công nếu bạn tự host/build bằng Firebase Project riêng)*:
   - Chọn **Add new provider → Email/Password**.
   - Bật công tắc **Enable** ở dòng **Email/Password** → nhấn **Save**.
   - *(Khuyên dùng)* Bật thêm **Email link** nếu muốn đăng nhập không cần mật khẩu, hoặc giữ tắt nếu chỉ dùng Email + Mật khẩu truyền thống.

### 1.4. Khởi Tạo Cloud Firestore & Bảo Mật (Security Rules)
1. Vào **Build → Firestore Database → Create database**.
2. Chọn Location gần người dùng (ví dụ `asia-southeast1` hoặc `asia-northeast1`).
   - Nếu dùng named database riêng (như cấu hình `firestore_database_id` trong `firebase_applet_config.xml`), đặt đúng **Database ID** tương ứng; hoặc nếu dùng database mặc định của riêng bạn, hãy cập nhật `firestore_database_id` thành `(default)` trong project Android.
3. Chọn **Start in production mode** → **Create**.
4. Chuyển sang tab **Rules**, dán toàn bộ nội dung từ file `firestore.rules` ở thư mục gốc dự án và nhấn **Publish**.
5. **Composite Index cho Bảng Xếp Hạng & Chat (nếu dùng Standard Firestore)**:
   - Bảng xếp hạng (`leaderboard`): `publicEntry` (`Ascending`), `points` (`Descending`).
   - Chat tổng (`global_chat`): `publicChannel` (`Ascending`), `createdAt` (`Descending`).
   - Chat trong phòng (`messages` subcollection): `memberUids` (`Arrays`), `createdAt` (`Descending`).
   - Nếu chạy app lần đầu gặp lỗi thiếu index trong Logcat, nhấn trực tiếp vào link Firebase in ra trong Logcat để tạo index tự động trong 1 phút.

---

## Phần 2: Cấu Hình Trong Android Project (`CaroXO`)

### 2.1. Đặt file cấu hình
- Copy file `google-services.json` tải từ Firebase Console vào đường dẫn:
  ```text
  app/google-services.json
  ```
- Kiểm tra file `app/src/main/res/values/firebase_applet_config.xml` đảm bảo `firestore_database_id` khớp với Database ID trên Firebase Console:
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <resources>
      <string name="firestore_database_id" translatable="false">YOUR_FIRESTORE_DATABASE_ID</string>
  </resources>
  ```
  > Lưu ý: Không tạo thủ công `default_web_client_id` trong `strings.xml` — Google Services Gradle Plugin tự tạo chuỗi này từ `google-services.json` (mục `oauth_client` có `client_type: 3`).

### 2.2. Kiểm tra Dependencies & Plugins
Dự án đã cấu hình sẵn trong `gradle/libs.versions.toml` và `app/build.gradle.kts`:
- Root `build.gradle.kts` & `app/build.gradle.kts`: áp dụng plugin `com.google.gms.google-services`.
- Thư viện trong `app/build.gradle.kts`:
  - `platform(libs.firebase.bom)`
  - `libs.firebase.auth`
  - `libs.firebase.firestore`
  - `libs.androidx.credentials`, `libs.androidx.credentials.play.services`, `libs.googleid` (cho Google Sign-In qua Jetpack Credential Manager).

### 2.3. Khởi tạo Firestore trong `CaroApplication.kt`
```kotlin
val repository: FirebaseGameRepository by lazy {
    val dbId = getString(R.string.firestore_database_id)
    val db = FirebaseFirestore.getInstance(dbId)
    FirebaseGameRepository(db)
}
```

---

## Phần 3: Đăng Nhập & Đăng Ký Email / Password (Đã Tích Hợp Sẵn Trong Source)

> **Lưu ý về môi trường AI Studio:** Backend tự động của AI Studio ưu tiên cấp phát **Google Sign-In** (Credential Manager) và **Cloud Firestore**. Trong mã nguồn dự án CaroXO, giao diện (`sheet_email_auth.xml`, `EmailAuthSheetHelper.kt`) và code xử lý (`AuthManager.signInWithEmail`, `AuthManager.registerWithEmail`, `AuthManager.sendPasswordReset`) đã được tích hợp sẵn đầy đủ ở các nút **"Đăng nhập / Đăng ký Email"**. Để sử dụng với Firebase Project của riêng bạn, chỉ cần bật **Email/Password** ở Mục 1.3 trên Firebase Console và đặt file `google-services.json` mới vào `app/`.

### 3.1. Các hàm xử lý có sẵn trong `AuthManager.kt`
```kotlin
fun signInWithEmail(
    email: String,
    password: String,
    scope: CoroutineScope,
    onAuthSuccess: (FirebaseUser) -> Unit,
    onAuthError: (String) -> Unit
) {
    val cleanEmail = email.trim()
    if (cleanEmail.isBlank() || password.length < 6) {
        onAuthError("Email không hợp lệ hoặc mật khẩu dưới 6 ký tự.")
        return
    }
    scope.launch {
        try {
            val result = FirebaseAuth.getInstance()
                .signInWithEmailAndPassword(cleanEmail, password)
                .await()
            val user = result.user
            if (user != null) onAuthSuccess(user)
            else onAuthError("Không thể đăng nhập.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onAuthError(e.localizedMessage ?: "Đăng nhập thất bại.")
        }
    }
}

fun registerWithEmail(
    email: String,
    password: String,
    nickname: String,
    scope: CoroutineScope,
    onAuthSuccess: (FirebaseUser) -> Unit,
    onAuthError: (String) -> Unit
) {
    val cleanEmail = email.trim()
    if (cleanEmail.isBlank() || password.length < 6) {
        onAuthError("Email không hợp lệ hoặc mật khẩu phải từ 6 ký tự trở lên.")
        return
    }
    scope.launch {
        try {
            val result = FirebaseAuth.getInstance()
                .createUserWithEmailAndPassword(cleanEmail, password)
                .await()
            val user = result.user
            if (user != null) {
                val profileUpdate = com.google.firebase.auth.userProfileChangeRequest {
                    displayName = nickname.trim().ifBlank { cleanEmail.substringBefore("@") }
                }
                user.updateProfile(profileUpdate).await()
                onAuthSuccess(user)
            } else {
                onAuthError("Không thể tạo tài khoản.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onAuthError(e.localizedMessage ?: "Đăng ký thất bại.")
        }
    }
}

fun sendPasswordReset(
    email: String,
    scope: CoroutineScope,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    scope.launch {
        try {
            FirebaseAuth.getInstance().sendPasswordResetEmail(email.trim()).await()
            onSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Không thể gửi email đặt lại mật khẩu.")
        }
    }
}
```

### 3.2. Tự động đồng bộ Profile khi dùng Email/Password
`MainViewModel` đã lắng nghe sẵn `AuthManager.authStateFlow()`. Ngay khi `signInWithEmail(...)` hoặc `registerWithEmail(...)` thành công, `MainViewModel.startAuthenticatedListeners(user)` sẽ tự động gọi `repository.ensureProfileInitialized(...)` để tạo tài liệu `users/{uid}` và `leaderboard/{uid}` (mặc định 100 điểm) trên Firestore giống hệt Google Sign-In.

---

## Phần 4: Kiểm Thử Security Rules Cục Bộ (Firestore Emulator)

Dự án đi kèm bộ kiểm thử bảo mật `firestore.test.js` và cấu hình `firebase.json`:
1. Khởi chạy Firestore Emulator (port `8085`):
   ```bash
   firebase emulators:start --only firestore
   ```
2. Chạy kiểm thử rules (ở terminal khác):
   ```bash
   FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js
   ```
3. Triển khai rules lên Firebase Project sau khi kiểm thử đạt:
   ```bash
   firebase deploy --only firestore:rules
   ```

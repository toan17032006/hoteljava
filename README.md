# HotelFlow — Hotel Management App

A Java Swing desktop application with SQLite. It does not use Spring. Requires Java 17 or later.

## Run the app

1. Install JDK 17+ and Maven 3.8+.
2. Open a terminal in the project folder and run `mvn clean package`.
3. Start the app with `java -jar target/hotel-manager-1.0.0.jar` (or run `mvn exec:java`).

### Run without Maven (Windows)

The app still needs its third-party libraries even when you build without Maven. Download these JAR files from Maven Central and place them in a `lib` folder in the project root:

- `sqlite-jdbc-3.47.1.0.jar`
- `pdfbox-3.0.4.jar`
- `pdfbox-io-3.0.4.jar`
- `fontbox-3.0.4.jar`
- `commons-logging-1.3.4.jar`

Then double-click `run.bat`, or open PowerShell in the project root and run:

```powershell
New-Item -ItemType Directory -Force out
javac -encoding UTF-8 -cp "lib/*" -d out src\main\java\vn\hotel\HotelApp.java
java -cp "out;lib/*" vn.hotel.HotelApp
```

### Run without Maven (Linux / macOS)

```bash
mkdir -p out
javac -encoding UTF-8 -cp "lib/*" -d out src/main/java/vn/hotel/HotelApp.java
java -cp "out:lib/*" vn.hotel.HotelApp
```

Do not run `java HotelApp.java` from inside `src\main\java\vn\hotel`; that command does not include the dependency JARs.

The app creates `hotel.db` in the working directory on first launch. Demo accounts:

| Role | Username | Password |
| --- | --- | --- |
| Admin | `admin` | `admin123` |
| Receptionist | `le_tan` | `123456` |
| Customer | `khach` | `123456` |

## Features

- Sign-in and role-based access for administrators, receptionists, and customers.
- Sign-up for new customers: from the Sign in screen, click "Sign up", enter full name, username, password (at least 6 characters), confirm password and optional phone. New accounts are always customers; staff accounts are still created only by an Admin in Staff management, so a visitor cannot give themselves admin or receptionist rights. After sign-up, the username is filled in on the Sign in screen — just enter the password and sign in. Sign-up does not reveal whether a username is already taken (see "Privacy and security" below), usernames are checked without distinguishing upper/lower case, and the Sign in screen no longer pre-fills any account or shows demo passwords.
- Privacy by role on the Dashboard: a customer sees only their own recent bookings and their own booking count — never other guests' bookings, other guests' phone numbers, or hotel revenue. Staff see all recent bookings and revenue, as before.
- Passwords are stored as SHA-256 hashes with a random salt (pure JDK, no new library). Accounts created before this change still sign in with their old password, and are automatically converted to a hash on their first successful sign-in, so no existing account breaks.
- Room management, rates, room types, and availability status.
- Bookings, check-in/check-out, and laundry or minibar charges.
- Automatic invoice calculation by nights and services; export as TXT or PDF.
- Revenue reports by day, month, or year, with a bar chart.
- Staff/customer account creation, booking history, room search, and customer reviews.
- Cleaning queue: rooms marked Cleaning after check-out can be marked as cleaned in one click to become Available again (sidebar: Cleaning).
- Booking search and filter: find bookings by guest name or room number, and filter by Reserved / Occupied / Checked out / Cancelled (Bookings & guests).
- Cancel and extend a stay: cancel a Reserved booking, or extend a Reserved/Occupied booking by extra nights with an overlap check (Bookings & guests).
- Invoice discount: add a 0–100% discount together with laundry/minibar charges; the invoice and check-out total show subtotal, discount, and total due.
- Export and backup: export bookings or rooms as CSV for Excel / Google Sheets, and back up `hotel.db` to a file of your choice (sidebar: Export & backup, for Admin and Receptionist).

## Hướng dẫn sử dụng theo tài khoản

Menu bên trái thay đổi theo vai trò sau khi đăng nhập. Tài khoản demo xem ở bảng trên.
Quy tắc chung: chọn 1 dòng trong bảng rồi mới bấm nút thao tác (ví dụ: chọn đặt phòng
rồi mới bấm Check-in, Check out, Cancel, Extend). Nếu chưa chọn dòng, app sẽ nhắc
“Please select a booking first.”

### Dùng chung cho cả 3 tài khoản

| Tính năng | Cách sử dụng | Mục đích |
| --- | --- | --- |
| Đăng nhập | Màn hình Sign in để trống, không tự điền sẵn tài khoản nào. Tự gõ Username và Password rồi bấm “Sign in”. Username không phân biệt hoa/thường (gõ `ADMIN` hay `admin` đều đăng nhập được tài khoản `admin` nếu đúng mật khẩu). Đăng nhập sai chỉ báo chung “Username or password is incorrect”, không nói rõ là sai username hay sai mật khẩu. | Xác thực người dùng và mở đúng menu theo vai trò, tránh khách xem/chỉnh dữ liệu vận hành, đồng thời tránh để lộ tài khoản mẫu cho người lạ mở app lên là thấy. |
| Đăng ký (Sign up) | Ở màn hình Sign in, bấm “Sign up”, nhập họ tên, username, mật khẩu (từ 6 ký tự), nhập lại mật khẩu để xác nhận và số điện thoại (không bắt buộc), bấm “Create account”. Sau đó username tự điền sẵn ở màn hình Sign in, chỉ cần nhập mật khẩu và bấm “Sign in”. | Khách mới tự tạo tài khoản mà không cần nhờ Admin, rồi tự đặt phòng ở menu “Find a room” và xem đơn ở “My bookings”. Tài khoản đăng ký luôn là Customer, không thể tự chọn Admin hay Receptionist, nên khách không thể tự cấp quyền nhân viên cho mình. |
| Quy tắc đăng ký | Username từ 3–30 ký tự, chỉ gồm chữ, số, gạch dưới, chấm hoặc gạch ngang, không có khoảng trắng; mật khẩu từ 6 ký tự và phải khớp với ô xác nhận; username được kiểm tra không phân biệt hoa/thường, nên không thể đăng ký `ADMIN` để mạo danh tài khoản `admin` đã có. Nếu username không tạo được, app chỉ báo chung “Could not create an account with that username. Please try signing in with it, or choose a different username.”, không nói thẳng là username đã có người dùng — để người lạ không dò được danh sách username đang có bằng cách thử đăng ký hàng loạt. | Tránh tài khoản trùng, mật khẩu quá yếu hoặc gõ sai mật khẩu mà không biết, tránh mạo danh bằng chữ hoa/thường, và tránh để lộ danh sách username cho người lạ. Lưu ý: cách này làm giảm dò username trực tiếp, nhưng không chặn tuyệt đối — kẻ đã biết username vẫn có thể thử đăng nhập, và username trong hệ thống khách sạn vốn không phải bí mật tuyệt đối (nhân viên biết tên khách). |
| Bảo mật mật khẩu | Mật khẩu tài khoản mới (kể cả tài khoản demo trên database mới và tài khoản do Admin tạo ở Staff) được lưu dưới dạng SHA-256 kèm salt ngẫu nhiên, không lưu dạng text thường. Tài khoản cũ lưu dạng text thường vẫn đăng nhập được bằng mật khẩu cũ, và sau lần đăng nhập đúng đầu tiên sẽ tự chuyển sang dạng hash. | Kẻ lấy được file `hotel.db` cũng không đọc thẳng được mật khẩu; mỗi tài khoản có salt riêng nên cùng một mật khẩu cũng cho hash khác nhau. Đây vẫn là hash SHA-256 thuần JDK (không dùng Spring hay thư viện mới), chưa phải là thuật toán mật khẩu chuyên dụng như bcrypt/Argon2. |
| Dashboard (Trang chính) | Mở mặc định sau khi đăng nhập, hoặc bấm “Dashboard” ở menu trái. | Admin và Lễ tân xem tổng số phòng, số phòng đang ở, doanh thu đã ghi nhận và 8 đặt phòng mới nhất của mọi khách. **Customer chỉ xem tổng số phòng, số phòng đang ở, số đơn của chính mình và 8 đặt phòng mới nhất của chính mình** — không thấy đơn của khách khác, không thấy số điện thoại khách khác, không thấy doanh thu. |
| Đăng xuất | Bấm “Sign out” ở cuối menu trái. | Kết thúc phiên làm việc và quay về màn hình đăng nhập, để người khác không dùng tiếp tài khoản của mình. |

### 1. Admin — toàn quyền vận hành và quản trị

Admin có tất cả tính năng của Lễ tân, cộng thêm quản lý nhân sự và doanh thu.
Admin không có menu “Find a room”, “My bookings”, “Reviews” dành cho khách;
muốn đặt phòng cho khách, Admin dùng “Bookings & guests”.

| Tính năng | Cách sử dụng | Mục đích |
| --- | --- | --- |
| Rooms — xem phòng | Bấm “Rooms”. Xem toàn bộ phòng, kể cả Available, Occupied và Cleaning. Nhập số phòng hoặc loại phòng vào ô Search rồi bấm “Search”. | Nắm được quỹ phòng và trạng thái từng phòng trước khi nhận khách hoặc sắp xếp dọn phòng. |
| Rooms — thêm phòng | Bấm “＋ Add room”, nhập số phòng, chọn loại Standard / Deluxe / VIP, nhập giá/đêm (VND) và trạng thái, bấm OK. | Mở thêm phòng mới khi khách sạn tăng quy mô, không cần sửa code hay sửa trực tiếp database. |
| Rooms — sửa phòng | Chọn 1 phòng trong bảng, bấm “Edit selected room”, sửa số phòng, loại, giá hoặc trạng thái, bấm OK. | Điều chỉnh giá theo mùa, đổi loại phòng, hoặc sửa trạng thái phòng khi có sự cố, mà vẫn giữ nguyên lịch sử đặt phòng. |
| Bookings & guests — tạo đặt phòng | Bấm “＋ New booking”, chọn khách (tài khoản CUSTOMER), chọn phòng còn trống, nhập ngày nhận phòng và ngày trả phòng dạng YYYY-MM-DD, bấm OK. | Tạo đặt phòng nhanh cho khách gọi điện/đến trực tiếp. App tự chặn nếu ngày trả phòng không sau ngày nhận phòng, hoặc phòng đã có đặt phòng trùng ngày ở trạng thái Reserved / Occupied. |
| Bookings & guests — tìm kiếm và lọc | Nhập tên khách hoặc số phòng vào ô Search, chọn trạng thái ở ô Status (All / Reserved / Occupied / Checked out / Cancelled), bấm “Search”. | Tìm đúng đặt phòng trong hàng trăm dòng mà không phải cuộn bảng, đặc biệt hữu ích khi khách hỏi về đơn của mình hoặc khi cần xử lý đơn cụ thể. |
| Bookings & guests — Check-in | Chọn đặt phòng trạng thái Reserved, bấm “Check-in”. | Xác nhận khách đã đến nhận phòng: đặt phòng chuyển thành Occupied và phòng chuyển thành Occupied, tránh bán trùng phòng đó cho khách khác. |
| Bookings & guests — thêm dịch vụ và giảm giá | Chọn đặt phòng trạng thái Occupied, bấm “＋ Add services / discount”, nhập tổng tiền giặt ủi, tổng tiền minibar/ăn uống và giảm giá 0–100%, bấm OK. Lưu ý: các số này sẽ ghi đè giá trị cũ, nên nhập tổng số tiền hiện tại, không nhập số cộng thêm. | Ghi nhận chi phí phát sinh trong thời gian khách ở và áp dụng ưu đãi (khách thân thiết, đặt dài ngày, xử lý khiếu nại) trước khi tính tiền, thay vì sửa tay hóa đơn sau đó. |
| Bookings & guests — Check out & invoice | Chọn đặt phòng trạng thái Occupied, bấm “Check out & invoice”, xem trước hóa đơn, bấm OK để xác nhận. | Kết thúc kỳ lưu trú và chốt tiền: ngày trả thực tế lấy theo ngày hôm nay, số đêm tối thiểu là 1, tổng tiền = số đêm × giá phòng + giặt ủi + minibar − giảm giá. Sau đó đặt phòng thành Checked out và phòng tự chuyển sang Cleaning để chờ dọn. |
| Bookings & guests — hủy đặt phòng | Chọn đặt phòng trạng thái Reserved, bấm “Cancel booking”, xác nhận. | Hủy đơn khách không đến mà không làm xáo trộn phòng đang ở hay đơn đã trả phòng; trạng thái chuyển thành Cancelled và phòng vẫn trống để bán tiếp. |
| Bookings & guests — gia hạn ở | Chọn đặt phòng trạng thái Reserved hoặc Occupied, bấm “Extend stay”, nhập số đêm muốn thêm (số nguyên > 0), bấm OK. | Cho khách ở thêm mà không phải hủy đơn cũ và tạo đơn mới. App tự chặn nếu khoảng ngày gia hạn trùng với đặt phòng khác của cùng phòng. |
| Cleaning — dọn phòng | Bấm “Cleaning” để xem các phòng đang ở trạng thái Cleaning, chọn 1 phòng, bấm “✓ Mark as cleaned → Available”. | Khép kín quy trình sau trả phòng: phòng chỉ bán lại khi đã dọn xong, tránh giao phòng chưa dọn cho khách mới. |
| Invoices — xem và xuất hóa đơn | Bấm “Invoices” để xem danh sách hóa đơn theo đặt phòng. Chọn 1 dòng, bấm “Export TXT” để xuất file .txt hoặc “Export PDF” để xuất file .pdf, chọn nơi lưu trong hộp thoại. | Cung cấp hóa đơn cho khách và lưu chứng từ thanh toán. Hóa đơn ghi rõ khách, số điện thoại, phòng, ngày ở, số đêm, tiền phòng, giặt ủi, minibar, tổng phụ, giảm giá, tổng phải trả và trạng thái. |
| Export & backup — xuất CSV và sao lưu | Bấm “Export & backup”, bấm “Export bookings CSV” hoặc “Export rooms CSV” rồi chọn nơi lưu, hoặc bấm “Back up hotel.db” rồi chọn nơi lưu (mặc định là `hotel-backup-YYYY-MM-DD.db`). | Xuất dữ liệu ra Excel / Google Sheets để đối soát, làm báo cáo ngoài app; sao lưu toàn bộ database để khôi phục khi máy hỏng, xóa nhầm hoặc chuyển máy. |
| Staff — tạo tài khoản | Bấm “Staff”, bấm “＋ Create account”, nhập họ tên, username, mật khẩu (ô mật khẩu đã che bằng dấu chấm, không hiện text thường như trước), chọn vai trò ADMIN / RECEPTIONIST / CUSTOMER và số điện thoại, bấm OK. Username trùng (kể cả khác hoa/thường) thì Admin — người đã thấy toàn bộ danh sách tài khoản ở màn hình Staff — sẽ được báo rõ là username đã có. | Quản trị nhân sự và khách hàng trong hệ thống: cấp tài khoản cho nhân viên mới, tạo tài khoản khách để lễ tân đặt phòng hộ, phân quyền rõ ràng ngay từ lúc tạo, và tránh để lộ mật khẩu khi gõ trước mặt người khác. |
| Revenue — báo cáo doanh thu | Bấm “Revenue”, chọn “Group by” là Day, Month hoặc Year. | Theo dõi doanh thu chỉ từ các đơn đã Checked out, nhóm theo ngày/tháng/năm (tối đa 12 kỳ gần nhất) kèm biểu đồ cột, phục vụ quyết định giá phòng, khuyến mãi và đánh giá hiệu quả kinh doanh. |

### 2. Receptionist (Lễ tân) — vận hành hằng ngày

Lễ tân làm được mọi việc vận hành như Admin ở các mục Dashboard, Rooms (xem),
Bookings & guests, Cleaning, Invoices và Export & backup, nhưng **không** được
thêm/sửa phòng, **không** được tạo tài khoản (Staff) và **không** được xem
Revenue. Lễ tân cũng không có menu “Find a room”, “My bookings”, “Reviews”
dành cho khách; muốn đặt phòng cho khách, Lễ tân dùng “Bookings & guests”.

| Tính năng | Cách sử dụng | Mục đích |
| --- | --- | --- |
| Rooms — xem và tìm phòng | Bấm “Rooms”, xem danh sách phòng. Nhập số phòng hoặc loại phòng vào ô Search rồi bấm “Search”. Không có nút Add room và Edit selected room. | Kiểm tra phòng trống, phòng đang ở và phòng đang dọn trước khi nhận khách, nhưng không làm thay đổi cấu hình giá/loại phòng (việc đó thuộc về Admin). |
| Bookings & guests — toàn bộ nghiệp vụ đặt phòng | Dùng đúng như Admin: tạo đặt phòng, tìm kiếm/lọc, Check-in, thêm dịch vụ và giảm giá, Check out & invoice, hủy đặt phòng Reserved, gia hạn đặt phòng Reserved / Occupied. | Đây là công việc chính của lễ tân trong ca trực: nhận đặt phòng, đón khách, ghi dịch vụ phát sinh, tính tiền trả phòng và xử lý thay đổi lịch ở, tất cả trong một màn hình. |
| Cleaning — dọn phòng | Bấm “Cleaning”, chọn phòng đang Cleaning, bấm “✓ Mark as cleaned → Available”. | Sau khi khách trả phòng, lễ tân phối hợp với bộ phận buồng phòng để đưa phòng trở lại trạng thái bán được càng sớm càng tốt, tránh để phòng trống vô lý. |
| Invoices — xem và xuất hóa đơn | Bấm “Invoices”, chọn 1 hóa đơn, bấm “Export TXT” hoặc “Export PDF” rồi chọn nơi lưu. | In/gửi hóa đơn cho khách ngay tại quầy khi trả phòng, không cần nhờ Admin và không cần phần mềm khác. |
| Export & backup — xuất CSV và sao lưu | Bấm “Export & backup”, xuất bookings/rooms ra CSV hoặc sao lưu `hotel.db` như Admin. | Cuối ca hoặc cuối ngày, lễ tân có thể xuất dữ liệu để bàn giao, đối soát và sao lưu, giảm rủi ro mất dữ liệu khi có sự cố. |

Luồng làm việc gợi ý cho Lễ tân: Dashboard → Bookings & guests (tạo đơn / Check-in)
→ thêm dịch vụ trong thời gian khách ở → Check out & invoice → Cleaning
(đánh dấu đã dọn xong) → phòng trở lại Available để bán tiếp.

### 3. Customer (Khách hàng) — tự đặt phòng và gửi phản hồi

Khách chỉ thấy menu của mình, không thấy Rooms quản trị, Bookings & guests,
Cleaning, Invoices, Staff, Revenue hay Export & backup.

| Tính năng | Cách sử dụng | Mục đích |
| --- | --- | --- |
| Find a room — tìm và đặt phòng | Bấm “Find a room”. Chỉ các phòng Available mới hiện ra. Nhập số phòng hoặc loại phòng vào ô Search rồi bấm “Search”, chọn 1 phòng, bấm “Book selected room”. Trong hộp thoại đặt phòng, khách đã được chọn sẵn là chính tài khoản đang đăng nhập, phòng đã được chọn sẵn, ngày nhận mặc định là hôm nay và ngày trả mặc định là ngày mai; sửa ngày nếu cần rồi bấm OK. | Cho khách tự chọn phòng và đặt phòng mà không cần gọi điện hay qua lễ tân. App vẫn chặn đặt trùng ngày với đơn Reserved / Occupied khác của cùng phòng, nên khách không đặt nhầm phòng đã có người. |
| My bookings — lịch sử đặt phòng của mình | Bấm “My bookings”. | Xem lại các đơn của chính mình: phòng, loại phòng, ngày nhận, ngày trả, trạng thái và số tiền đã ghi nhận, để đối chiếu trước khi đến, gia hạn qua lễ tân hoặc khiếu nại nếu có sai lệch. |
| Reviews — gửi đánh giá | Bấm “Reviews”, chọn sao từ 5 xuống 1, nhập nhận xét vào ô Comment, bấm “Submit review”. | Thu thập phản hồi thật từ khách sau kỳ nghỉ để khách sạn cải thiện dịch vụ; đánh giá được lưu vào database cùng tài khoản khách gửi. |

Luồng làm việc gợi ý cho Khách: Find a room (chọn phòng trống) → đặt phòng →
My bookings (kiểm tra lại đơn) → sau khi ở, Reviews (gửi đánh giá).

### Phân quyền tóm tắt

| Menu / tính năng | Admin | Receptionist | Customer |
| --- | --- | --- | --- |
| Dashboard | Có | Có | Có |
| Rooms (xem) | Có | Có | Chỉ phòng trống, qua “Find a room” |
| Rooms (thêm / sửa) | Có | Không | Không |
| Bookings & guests (tạo, check-in, dịch vụ, trả phòng, hủy, gia hạn, tìm kiếm) | Có | Có | Không |
| Cleaning | Có | Có | Không |
| Invoices (xuất TXT / PDF) | Có | Có | Không |
| Export & backup (CSV, sao lưu DB) | Có | Có | Không |
| Staff (tạo tài khoản) | Có | Không | Không |
| Revenue (báo cáo, biểu đồ) | Có | Không | Không |
| Find a room (tự đặt phòng) | Không | Không | Có |
| My bookings (đơn của mình) | Không | Không | Có |
| Reviews (gửi đánh giá) | Không | Không | Có |

Passwords for new accounts (sign-up, Staff creation, and demo accounts on a fresh database) are stored as SHA-256 hashes with a random salt, using only the JDK. A database created before this change may still contain plain-text demo passwords; those accounts still sign in normally and are converted to a hash on their first successful sign-in. SHA-256 with salt is a step up from plain text, but a production system should use a dedicated password-hashing algorithm such as bcrypt or Argon2 instead.

## Privacy and security — what was fixed, and what still needs care

These fixes are all pure Java (JDK only, no Spring, no new library):

- **Sign-up no longer confirms whether a username exists.** Trying to register an existing username (in any upper/lower case) now shows only a generic message asking you to try signing in with it or choose a different username, creates no second account, and leaves the existing account's password untouched. This stops bulk username enumeration through the sign-up form. The Staff screen keeps a clear "already exists" message because an Admin there already sees the full user list.
- **The Sign in screen no longer leaks an account.** It used to pre-fill `admin` / `admin123` and print all demo passwords underneath, so anyone opening the app could click Sign in and become Admin without knowing anything. Now both fields start empty, no demo password is shown on screen, and demo accounts are documented only here in README for development. Before any real use, change or delete the demo accounts (`admin`, `le_tan`, `khach`) and do not leave their passwords in this file.
- **A customer can no longer see other guests' data on the Dashboard.** Before, every role saw all recent bookings and total revenue on the home page, so a newly signed-up customer could see other guests' names, rooms and dates, plus hotel revenue. Now a customer sees only their own recent bookings and their own booking count; revenue and other guests' bookings are staff-only. Customers still see only their own bookings in "My bookings", only available rooms in "Find a room", and no other user's phone number anywhere in their menus.
- **Passwords are masked where they are typed.** Staff account creation used a plain text field for the password; it now uses a password field (dots). Sign-up already used password fields. Sign-up failures no longer show raw database error text to an unauthenticated user; they show a generic "could not be created" message instead.
- **Usernames are case-insensitive for login and sign-up.** `ADMIN` cannot be registered to impersonate `admin`, and logging in as `ADMIN` with the right password signs in as `admin`. Login failures still give only the generic "incorrect" message, without saying whether the username or the password was wrong.
- **What is not fixed by this change:** usernames themselves are not secret in a hotel system (staff know guest names, and bookings show guest names to staff), so a determined person who already knows a username can still try to sign in as that user — the protection here is the password, the hash, and changing the demo passwords. File `hotel.db` itself must still be kept safe: anyone with the file and the app can try to sign in, and an Admin can see all users' names, usernames, roles and phone numbers in Staff management (that is the Admin's job, not a customer feature).

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

Do not run `java HotelApp.java` from inside `src\main\java\vn\hotel`; that command does not include the dependency JARs.

The app creates `hotel.db` in the working directory on first launch. Demo accounts:

| Role | Username | Password |
| --- | --- | --- |
| Admin | `admin` | `admin123` |
| Receptionist | `le_tan` | `123456` |
| Customer | `khach` | `123456` |

## Features

- Sign-in and role-based access for administrators, receptionists, and customers.
- Room management, rates, room types, and availability status.
- Bookings, check-in/check-out, and laundry or minibar charges.
- Automatic invoice calculation by nights and services; export as TXT or PDF.
- Revenue reports by day, month, or year, with a bar chart.
- Staff/customer account creation, booking history, room search, and customer reviews.

Demo passwords are stored as plain text for this coursework example. A production system should store password hashes instead.

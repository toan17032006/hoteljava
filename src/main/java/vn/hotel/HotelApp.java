package vn.hotel;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.file.Files;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.text.Normalizer;
import java.util.*;

/** Hotel desk: Swing UI, SQLite persistence, role-based screens and invoice export. */
public class HotelApp {
    static final String DB="hotel.db";
    static final Color NAVY=new Color(22,38,57), TEAL=new Color(0,137,123), BG=new Color(244,247,250), INK=new Color(36,49,66);
    static Connection db;
    static User user;
    record User(int id,String name,String role){}
    record Room(int id,String number,String type,double price,String status,int capacity,String amenities,String description,String imagePath,int active){}
    static String money(double n){return String.format(Locale.US,"%,.0f VND",n);}
    static void initDb() throws Exception {
        db=DriverManager.getConnection("jdbc:sqlite:"+DB);
        try(Statement s=db.createStatement()){
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, username TEXT UNIQUE NOT NULL, password TEXT NOT NULL, role TEXT NOT NULL, phone TEXT DEFAULT '')");
            s.execute("CREATE TABLE IF NOT EXISTS rooms(id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT UNIQUE NOT NULL, type TEXT NOT NULL, price REAL NOT NULL, status TEXT NOT NULL DEFAULT 'Available')");
            s.execute("CREATE TABLE IF NOT EXISTS bookings(id INTEGER PRIMARY KEY AUTOINCREMENT, guest_id INTEGER NOT NULL REFERENCES users(id), room_id INTEGER NOT NULL REFERENCES rooms(id), checkin TEXT NOT NULL, checkout TEXT NOT NULL, actual_out TEXT, status TEXT NOT NULL DEFAULT 'Reserved', laundry REAL NOT NULL DEFAULT 0, minibar REAL NOT NULL DEFAULT 0, total REAL NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS reviews(id INTEGER PRIMARY KEY AUTOINCREMENT, guest_id INTEGER REFERENCES users(id), rating INTEGER, comment TEXT, created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
        }
        // Migrate status values and demo names stored by earlier Vietnamese versions.
        try(Statement s=db.createStatement()){
            s.executeUpdate("UPDATE rooms SET status='Available' WHERE status='Trống'");
            s.executeUpdate("UPDATE rooms SET status='Occupied' WHERE status='Đang ở'");
            s.executeUpdate("UPDATE rooms SET status='Cleaning' WHERE status='Đang dọn dẹp'");
            s.executeUpdate("UPDATE bookings SET status='Reserved' WHERE status='Đặt trước'");
            s.executeUpdate("UPDATE bookings SET status='Occupied' WHERE status='Đang ở'");
            s.executeUpdate("UPDATE bookings SET status='Checked out' WHERE status='Đã trả phòng'");
            s.executeUpdate("UPDATE users SET name='Administrator' WHERE username='admin'");
            s.executeUpdate("UPDATE users SET name='Receptionist' WHERE username='le_tan'");
            s.executeUpdate("UPDATE users SET name='Sample customer' WHERE username='khach'");
        }
        try(Statement s=db.createStatement()){s.execute("ALTER TABLE bookings ADD COLUMN discount REAL NOT NULL DEFAULT 0");}catch(Exception ignored){}
        ensureSchema();
        seed("admin","admin123","Administrator","ADMIN","0900000001");seed("le_tan","123456","Receptionist","RECEPTIONIST","0900000002");seed("khach","123456","Sample customer","CUSTOMER","0900000003");
        String[][] rooms={{"101","Standard","550000"},{"102","Standard","550000"},{"201","Deluxe","900000"},{"202","Deluxe","900000"},{"301","VIP","1600000"},{"302","VIP","1600000"}};
        for(String[] r:rooms)try(PreparedStatement p=db.prepareStatement("INSERT OR IGNORE INTO rooms(number,type,price) VALUES(?,?,?)")){p.setString(1,r[0]);p.setString(2,r[1]);p.setDouble(3,Double.parseDouble(r[2]));p.executeUpdate();}
        try(Statement s=db.createStatement()){s.execute("INSERT OR IGNORE INTO room_types(name,base_price,capacity,amenities) SELECT type, MIN(price), MIN(capacity), MIN(amenities) FROM rooms GROUP BY type");}catch(Exception ignored){}
    }
    static void seed(String u,String pw,String n,String role,String phone)throws Exception{try(PreparedStatement p=db.prepareStatement("INSERT OR IGNORE INTO users(name,username,password,role,phone) VALUES(?,?,?,?,?)")){p.setString(1,n);p.setString(2,u);p.setString(3,encodePassword(pw));p.setString(4,role);p.setString(5,phone);p.executeUpdate();}}


    // ---------- Schema upgrades, settings, pricing, availability, payments ----------
    // All upgrades are additive (new columns/tables only) so an existing hotel.db
    // keeps its bookings, users and rooms. Pure JDK + SQLite, no new library.
    static void addColumn(String table,String col,String def)throws Exception{
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")){while(r.next()){if(col.equals(r.getString(2)))return;}}
        try(Statement s=db.createStatement()){s.execute("ALTER TABLE "+table+" ADD COLUMN "+col+" "+def);}
    }
    static void ensureSchema()throws Exception{
        try(Statement s=db.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS service_catalog(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, category TEXT NOT NULL DEFAULT 'Other', description TEXT DEFAULT '', price REAL NOT NULL DEFAULT 0, service_hours TEXT DEFAULT '', available INTEGER NOT NULL DEFAULT 1)");
            s.execute("CREATE TABLE IF NOT EXISTS booking_services(id INTEGER PRIMARY KEY AUTOINCREMENT, booking_id INTEGER NOT NULL, service_id INTEGER, service_name TEXT NOT NULL, unit_price REAL NOT NULL DEFAULT 0, quantity INTEGER NOT NULL DEFAULT 1, amount REAL NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'Confirmed', created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS service_requests(id INTEGER PRIMARY KEY AUTOINCREMENT, booking_id INTEGER NOT NULL, guest_id INTEGER NOT NULL, service_id INTEGER, service_name TEXT NOT NULL, unit_price REAL NOT NULL DEFAULT 0, quantity INTEGER NOT NULL DEFAULT 1, amount REAL NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'Pending', created_at TEXT DEFAULT CURRENT_TIMESTAMP, note TEXT DEFAULT '')");
            s.execute("CREATE TABLE IF NOT EXISTS payments(id INTEGER PRIMARY KEY AUTOINCREMENT, booking_id INTEGER NOT NULL, method TEXT NOT NULL DEFAULT 'Cash', type TEXT NOT NULL DEFAULT 'Partial', amount REAL NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'Completed', note TEXT DEFAULT '', created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS support_requests(id INTEGER PRIMARY KEY AUTOINCREMENT, guest_id INTEGER NOT NULL, booking_id INTEGER, subject TEXT NOT NULL, message TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'Open', reply TEXT DEFAULT '', created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS shift_notes(id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, username TEXT, note TEXT NOT NULL, created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS staff_shifts(id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, shift_date TEXT NOT NULL, shift_name TEXT NOT NULL, note TEXT DEFAULT '')");
            s.execute("CREATE TABLE IF NOT EXISTS housekeeping_tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, room_id INTEGER NOT NULL, task_type TEXT NOT NULL, assigned_to TEXT DEFAULT '', status TEXT NOT NULL DEFAULT 'Pending', note TEXT DEFAULT '', created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS price_rules(id INTEGER PRIMARY KEY AUTOINCREMENT, scope_type TEXT NOT NULL DEFAULT 'ALL', scope_value TEXT DEFAULT '', start_date TEXT NOT NULL, end_date TEXT NOT NULL, price REAL NOT NULL, weekend_only INTEGER NOT NULL DEFAULT 0, note TEXT DEFAULT '')");
            s.execute("CREATE TABLE IF NOT EXISTS promotions(id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT UNIQUE NOT NULL, discount_percent REAL NOT NULL DEFAULT 0, start_date TEXT NOT NULL, end_date TEXT NOT NULL, room_type TEXT NOT NULL DEFAULT 'ALL', active INTEGER NOT NULL DEFAULT 1)");
            s.execute("CREATE TABLE IF NOT EXISTS audit_log(id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, username TEXT, action TEXT NOT NULL, detail TEXT DEFAULT '', created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS room_types(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL, description TEXT DEFAULT '', base_price REAL NOT NULL DEFAULT 0, capacity INTEGER NOT NULL DEFAULT 2, amenities TEXT DEFAULT '', active INTEGER NOT NULL DEFAULT 1)");
            s.execute("CREATE TABLE IF NOT EXISTS notifications(id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, booking_id INTEGER, type TEXT NOT NULL DEFAULT 'Info', title TEXT NOT NULL, message TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'Unread', created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
        }
        addColumn("users","email","TEXT DEFAULT ''");addColumn("users","notifications","INTEGER NOT NULL DEFAULT 1");addColumn("users","is_locked","INTEGER NOT NULL DEFAULT 0");addColumn("users","care_notes","TEXT DEFAULT ''");
        addColumn("rooms","image_blob","BLOB");
        addColumn("rooms","capacity","INTEGER NOT NULL DEFAULT 2");addColumn("rooms","amenities","TEXT DEFAULT 'Wi-Fi, TV, Điều hòa'");addColumn("rooms","description","TEXT DEFAULT ''");addColumn("rooms","image_path","TEXT DEFAULT ''");addColumn("rooms","is_active","INTEGER NOT NULL DEFAULT 1");
        addColumn("bookings","booking_code","TEXT DEFAULT ''");addColumn("bookings","guests","INTEGER NOT NULL DEFAULT 1");addColumn("bookings","occupants","TEXT DEFAULT ''");addColumn("bookings","special_requests","TEXT DEFAULT ''");addColumn("bookings","stay_notes","TEXT DEFAULT ''");addColumn("bookings","status_note","TEXT DEFAULT ''");addColumn("bookings","cancel_reason","TEXT DEFAULT ''");addColumn("bookings","cancel_fee","REAL NOT NULL DEFAULT 0");addColumn("bookings","promo_code","TEXT DEFAULT ''");addColumn("bookings","promo_discount","REAL NOT NULL DEFAULT 0");addColumn("bookings","tax_amount","REAL NOT NULL DEFAULT 0");addColumn("bookings","room_amount","REAL NOT NULL DEFAULT 0");addColumn("bookings","payment_status","TEXT DEFAULT 'Unpaid'");addColumn("bookings","identity_verified","INTEGER NOT NULL DEFAULT 0");
        addColumn("reviews","booking_id","INTEGER");addColumn("reviews","service_rating","INTEGER");addColumn("reviews","reply","TEXT DEFAULT ''");addColumn("reviews","status","TEXT DEFAULT 'Approved'");
        try(Statement s=db.createStatement()){
            s.execute("UPDATE bookings SET booking_code='HF-'||printf('%06d',id) WHERE booking_code IS NULL OR booking_code=''");
            s.execute("UPDATE rooms SET capacity=2, amenities='Wi-Fi, TV, Điều hòa, Nước nóng', description='Phòng Standard thoải mái cho 2 khách.' WHERE type='Standard' AND (description IS NULL OR description='')");
            s.execute("UPDATE rooms SET capacity=3, amenities='Wi-Fi, TV, Điều hòa, Minibar, Ban công', description='Phòng Deluxe rộng rãi, view đẹp.' WHERE type='Deluxe' AND (description IS NULL OR description='')");
            s.execute("UPDATE rooms SET capacity=4, amenities='Wi-Fi, TV, Điều hòa, Minibar, Bồn tắm, Phòng khách', description='Phòng VIP sang trọng cho gia đình.' WHERE type='VIP' AND (description IS NULL OR description='')");
            s.execute("UPDATE users SET email='admin@hotel.local' WHERE username='admin' AND (email IS NULL OR email='')");
            s.execute("UPDATE users SET email='letan@hotel.local' WHERE username='le_tan' AND (email IS NULL OR email='')");
            s.execute("UPDATE users SET email='khach@hotel.local' WHERE username='khach' AND (email IS NULL OR email='')");
        }
        String[][] settings={{"hotel_name","HotelFlow"},{"hotel_phone","0900000000"},{"hotel_email","info@hotelflow.local"},{"hotel_address",""},{"checkin_time","14:00"},{"checkout_time","12:00"},{"currency","VND"},{"tax_rate","0"},{"deposit_percent","30"},{"cancellation_free_days","2"},{"late_cancel_refund_percent","50"},{"cancellation_policy","Hủy trước ngày nhận phòng 2 ngày trở lên: hoàn 100% số đã trả. Hủy trong 1 ngày trước nhận phòng: hoàn 50%. Hủy sau khi đã nhận phòng hoặc không đến: không hoàn, trừ khi Admin quyết định khác."}};
        for(String[] kv:settings)try(PreparedStatement q=db.prepareStatement("INSERT OR IGNORE INTO settings(key,value) VALUES(?,?)")){q.setString(1,kv[0]);q.setString(2,kv[1]);q.executeUpdate();}
        try(Statement s=db.createStatement()){s.execute("INSERT OR IGNORE INTO room_types(name,base_price,capacity,amenities) SELECT type, MIN(price), MIN(capacity), MIN(amenities) FROM rooms GROUP BY type");}catch(Exception ignored){}
        if(scalar("SELECT COUNT(*) FROM service_catalog")==0){
            String[][] svc={{"Bữa sáng buffet","Dining","Bữa sáng buffet tại nhà hàng","120000","06:00-10:00"},{"Bữa trưa","Dining","Suất ăn trưa","180000","11:00-14:00"},{"Bữa tối","Dining","Suất ăn tối","250000","17:00-21:00"},{"Giặt là (theo món)","Laundry","Giặt là, tính theo món","30000","08:00-20:00"},{"Spa 60 phút","Spa","Liệu trình spa 60 phút","500000","09:00-21:00"},{"Đưa đón sân bay (1 chiều)","Transport","Xe đưa đón sân bay","400000","Cả ngày"},{"Giường phụ","Other","Giường phụ cho 1 khách","200000","Cả ngày"}};
            for(String[] v:svc)try(PreparedStatement q=db.prepareStatement("INSERT INTO service_catalog(name,category,description,price,service_hours,available) VALUES(?,?,?,?,?,1)")){q.setString(1,v[0]);q.setString(2,v[1]);q.setString(3,v[2]);q.setDouble(4,Double.parseDouble(v[3]));q.setString(5,v[4]);q.executeUpdate();}
        }
    }
    static String setting(String key,String def)throws Exception{try(PreparedStatement q=db.prepareStatement("SELECT value FROM settings WHERE key=?")){q.setString(1,key);try(ResultSet r=q.executeQuery()){return r.next()?r.getString(1):def;}}}
    static double settingDouble(String key,double def)throws Exception{try{return Double.parseDouble(setting(key,String.valueOf(def)));}catch(Exception e){return def;}}
    static int settingInt(String key,int def)throws Exception{try{return Integer.parseInt(setting(key,String.valueOf(def)));}catch(Exception e){return def;}}
    static void setSetting(String key,String value)throws Exception{try(PreparedStatement q=db.prepareStatement("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){q.setString(1,key);q.setString(2,value);q.executeUpdate();}}
    static void logAction(String action,String detail){try{try(PreparedStatement q=db.prepareStatement("INSERT INTO audit_log(user_id,username,action,detail,created_at) VALUES(?,?,?,?,?)")){q.setObject(1,user==null?null:user.id());q.setString(2,user==null?"system":user.name());q.setString(3,action);q.setString(4,detail==null?"":detail);q.setString(5,LocalDateTime.now().toString());q.executeUpdate();}}catch(Exception ignored){}}
    static void createRoomType(String name,String description,double basePrice,int capacity,String amenities)throws Exception{
        if(name==null||name.trim().isEmpty())throw new IllegalArgumentException("Room type name is required.");
        if(capacity<=0)throw new IllegalArgumentException("Capacity must be greater than 0.");
        try(PreparedStatement q=db.prepareStatement("SELECT COUNT(*) FROM room_types WHERE LOWER(name)=LOWER(?)")){q.setString(1,name.trim());try(ResultSet r=q.executeQuery()){if(r.next()&&r.getInt(1)>0)throw new IllegalArgumentException("A room type with this name already exists.");}}
        try(PreparedStatement p=db.prepareStatement("INSERT INTO room_types(name,description,base_price,capacity,amenities,active) VALUES(?,?,?,?,?,1)")){p.setString(1,name.trim());p.setString(2,description==null?"":description);p.setDouble(3,basePrice);p.setInt(4,capacity);p.setString(5,amenities==null?"":amenities);p.executeUpdate();}
    }
    static void createNotification(int userId,Integer bookingId,String type,String title,String message)throws Exception{
        try(PreparedStatement p=db.prepareStatement("INSERT INTO notifications(user_id,booking_id,type,title,message,status,created_at) VALUES(?,?,?,?,?, 'Unread', ?)")){p.setInt(1,userId);if(bookingId==null)p.setNull(2,Types.INTEGER);else p.setInt(2,bookingId);p.setString(3,type);p.setString(4,title);p.setString(5,message);p.setString(6,LocalDateTime.now().toString());p.executeUpdate();}
    }
    static void notifyGuest(int bookingId,String type,String title,String message)throws Exception{
        try(PreparedStatement q=db.prepareStatement("SELECT guest_id FROM bookings WHERE id=?")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){if(r.next())createNotification(r.getInt(1),bookingId,type,title,message);}}
    }
    static void updateBookingInfo(int bookingId,int newGuestId,int newRoomId,LocalDate newIn,LocalDate newOut,int guests,String occupants,String special,String stayNotes,boolean isAdmin)throws Exception{
        int oldRoom,oldGuest;LocalDate oldIn,oldOut;String status;
        try(PreparedStatement q=db.prepareStatement("SELECT room_id,guest_id,checkin,checkout,status FROM bookings WHERE id=?")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){if(!r.next())throw new Exception("Booking not found.");oldRoom=r.getInt(1);oldGuest=r.getInt(2);oldIn=LocalDate.parse(r.getString(3));oldOut=LocalDate.parse(r.getString(4));status=r.getString(5);}}
        if(!"Reserved".equals(status)&&!"Occupied".equals(status))throw new IllegalArgumentException("Only a Reserved or checked-in booking can be edited.");
        if(newGuestId!=oldGuest&&"Occupied".equals(status)&&!isAdmin)throw new IllegalArgumentException("Only an Admin can change the guest of a checked-in booking.");
        if(newIn==null||newOut==null||!newOut.isAfter(newIn))throw new IllegalArgumentException("Check-out date must be after check-in date.");
        if(guests<=0)throw new IllegalArgumentException("Number of guests must be greater than 0.");
        if(newRoomId!=oldRoom||!newIn.equals(oldIn)||!newOut.equals(oldOut)){
            if(!isRoomAvailable(newRoomId,newIn,newOut,bookingId))throw new IllegalArgumentException("The new room / dates are not available (room closed, or overlapping booking).");
        }
        try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET guest_id=?,room_id=?,checkin=?,checkout=?,guests=?,occupants=?,special_requests=?,stay_notes=? WHERE id=?")){p.setInt(1,newGuestId);p.setInt(2,newRoomId);p.setString(3,newIn.toString());p.setString(4,newOut.toString());p.setInt(5,guests);p.setString(6,occupants==null?"":occupants);p.setString(7,special==null?"":special);p.setString(8,stayNotes==null?"":stayNotes);p.setInt(9,bookingId);p.executeUpdate();}
        if(newRoomId!=oldRoom&&"Occupied".equals(status)){
            try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET status='Cleaning' WHERE id=?")){p.setInt(1,oldRoom);p.executeUpdate();}
            try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET status='Occupied' WHERE id=?")){p.setInt(1,newRoomId);p.executeUpdate();}
        }
        logAction("Edit booking","Booking "+bookingId+" edited by staff");
    }
    static double roomBasePrice(int roomId)throws Exception{try(PreparedStatement q=db.prepareStatement("SELECT price FROM rooms WHERE id=?")){q.setInt(1,roomId);try(ResultSet r=q.executeQuery()){return r.next()?r.getDouble(1):0;}}}
    static String roomTypeOf(int roomId)throws Exception{try(PreparedStatement q=db.prepareStatement("SELECT type FROM rooms WHERE id=?")){q.setInt(1,roomId);try(ResultSet r=q.executeQuery()){return r.next()?r.getString(1):"";}}}
    static String roomNumberOf(int roomId)throws Exception{try(PreparedStatement q=db.prepareStatement("SELECT number FROM rooms WHERE id=?")){q.setInt(1,roomId);try(ResultSet r=q.executeQuery()){return r.next()?r.getString(1):"";}}}
    static double priceForNight(int roomId,LocalDate date)throws Exception{
        double base=roomBasePrice(roomId);String type=roomTypeOf(roomId);
        boolean weekend=date.getDayOfWeek()==DayOfWeek.SATURDAY||date.getDayOfWeek()==DayOfWeek.SUNDAY;
        // Priority: specific room > room type > all rooms; newest rule wins within a scope.
        try(PreparedStatement q=db.prepareStatement("SELECT price FROM price_rules WHERE start_date<=? AND end_date>=? AND (weekend_only=0 OR ?=1) AND ((scope_type='ROOM' AND scope_value=?) OR (scope_type='TYPE' AND scope_value=?) OR scope_type='ALL') ORDER BY CASE scope_type WHEN 'ROOM' THEN 3 WHEN 'TYPE' THEN 2 ELSE 1 END DESC, id DESC LIMIT 1")){
            q.setString(1,date.toString());q.setString(2,date.toString());q.setInt(3,weekend?1:0);q.setString(4,String.valueOf(roomId));q.setString(5,type);
            try(ResultSet r=q.executeQuery()){if(r.next())return r.getDouble(1);}
        }
        return base;
    }
    static double roomAmount(int roomId,LocalDate in,LocalDate out)throws Exception{double sum=0;for(LocalDate d=in;d.isBefore(out);d=d.plusDays(1))sum+=priceForNight(roomId,d);return sum;}
    static boolean isRoomAvailable(int roomId,LocalDate in,LocalDate out,int excludeBookingId)throws Exception{
        try(PreparedStatement q=db.prepareStatement("SELECT status,is_active FROM rooms WHERE id=?")){q.setInt(1,roomId);try(ResultSet r=q.executeQuery()){if(!r.next())return false;if(r.getInt(2)==0)return false;String st=r.getString(1);if("Maintenance".equals(st)||"Closed".equals(st))return false;}}
        try(PreparedStatement q=db.prepareStatement("SELECT COUNT(*) FROM bookings WHERE room_id=? AND id<>? AND status IN ('Reserved','Occupied') AND checkin < ? AND checkout > ?")){q.setInt(1,roomId);q.setInt(2,excludeBookingId);q.setString(3,out.toString());q.setString(4,in.toString());try(ResultSet r=q.executeQuery()){return !(r.next()&&r.getInt(1)>0);}}
    }
    static double confirmedServicesAmount(int bookingId)throws Exception{try(PreparedStatement q=db.prepareStatement("SELECT COALESCE(SUM(amount),0) FROM booking_services WHERE booking_id=? AND status='Confirmed'")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){return r.next()?r.getDouble(1):0;}}}
    static double legacyServicesAmount(int bookingId)throws Exception{try(PreparedStatement q=db.prepareStatement("SELECT COALESCE(laundry,0)+COALESCE(minibar,0) FROM bookings WHERE id=?")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){return r.next()?r.getDouble(1):0;}}}
    static double servicesTotal(int bookingId)throws Exception{return confirmedServicesAmount(bookingId)+legacyServicesAmount(bookingId);}
    static double billSubtotal(int bookingId)throws Exception{
        int roomId;LocalDate in,out;
        try(PreparedStatement q=db.prepareStatement("SELECT room_id,checkin,checkout FROM bookings WHERE id=?")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){if(!r.next())return 0;roomId=r.getInt(1);in=LocalDate.parse(r.getString(2));out=LocalDate.parse(r.getString(3));}}
        return roomAmount(roomId,in,out)+servicesTotal(bookingId);
    }
    static double billDiscountPct(int bookingId)throws Exception{try(PreparedStatement q=db.prepareStatement("SELECT COALESCE(discount,0)+COALESCE(promo_discount,0) FROM bookings WHERE id=?")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){return r.next()?Math.min(100,r.getDouble(1)):0;}}}
    static double billTotal(int bookingId)throws Exception{
        String status;double stored;
        try(PreparedStatement q=db.prepareStatement("SELECT status,COALESCE(total,0) FROM bookings WHERE id=?")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){if(!r.next())return 0;status=r.getString(1);stored=r.getDouble(2);}}
        if("Checked out".equals(status)&&stored>0)return stored;
        double sub=billSubtotal(bookingId);double disc=sub*billDiscountPct(bookingId)/100;double tax=(sub-disc)*settingDouble("tax_rate",0)/100;return sub-disc+tax;
    }
    static double paidNet(int bookingId)throws Exception{try(PreparedStatement q=db.prepareStatement("SELECT COALESCE(SUM(CASE WHEN type='Refund' THEN -amount ELSE amount END),0) FROM payments WHERE booking_id=? AND status='Completed'")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){return r.next()?r.getDouble(1):0;}}}
    static String computePaymentStatus(int bookingId)throws Exception{
        String status;try(PreparedStatement q=db.prepareStatement("SELECT status FROM bookings WHERE id=?")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){if(!r.next())return "Unpaid";status=r.getString(1);}}
        double total=billTotal(bookingId);double paid=paidNet(bookingId);boolean hasRefund=false,hasDeposit=false;
        try(PreparedStatement q=db.prepareStatement("SELECT type FROM payments WHERE booking_id=? AND status='Completed'")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){while(r.next()){if("Refund".equals(r.getString(1)))hasRefund=true;if("Deposit".equals(r.getString(1)))hasDeposit=true;}}}
        if(("Cancelled".equals(status)||"No-show".equals(status))&&hasRefund)return paid<=0.5?"Refunded":"Partially refunded";
        if(paid<=0.5)return "Unpaid";
        if(total>0&&paid>=total-0.5)return "Paid";
        return hasDeposit?"Deposit":"Partial";
    }
    static void updatePaymentStatus(int bookingId)throws Exception{try(PreparedStatement q=db.prepareStatement("UPDATE bookings SET payment_status=? WHERE id=?")){q.setString(1,computePaymentStatus(bookingId));q.setInt(2,bookingId);q.executeUpdate();}}
    static double refundForCancel(int bookingId,LocalDate today)throws Exception{
        LocalDate in;try(PreparedStatement q=db.prepareStatement("SELECT checkin FROM bookings WHERE id=?")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){if(!r.next())return 0;in=LocalDate.parse(r.getString(1));}}
        double paid=paidNet(bookingId);if(paid<=0)return 0;
        if(!today.isBefore(in))return 0;
        long daysBefore=ChronoUnit.DAYS.between(today,in);
        if(daysBefore>=settingInt("cancellation_free_days",2))return paid;
        return paid*settingDouble("late_cancel_refund_percent",50)/100;
    }
    static double promoDiscount(String code,String roomType,LocalDate date)throws Exception{
        if(code==null||code.trim().isEmpty())return 0;
        try(PreparedStatement q=db.prepareStatement("SELECT discount_percent FROM promotions WHERE UPPER(code)=UPPER(?) AND active=1 AND start_date<=? AND end_date>=? AND (room_type='ALL' OR room_type=?) ORDER BY id DESC LIMIT 1")){q.setString(1,code.trim());q.setString(2,date.toString());q.setString(3,date.toString());q.setString(4,roomType);try(ResultSet r=q.executeQuery()){return r.next()?r.getDouble(1):0;}}
    }
    static String bookingCodeFor(int id){return "HF-"+String.format("%06d",id);}
    static LocalDate parseDate(String s){try{return LocalDate.parse(s.trim());}catch(Exception e){return null;}}
    static boolean isLocked(String username)throws Exception{try(PreparedStatement q=db.prepareStatement("SELECT is_locked FROM users WHERE LOWER(username)=LOWER(?)")){q.setString(1,username);try(ResultSet r=q.executeQuery()){return r.next()&&r.getInt(1)==1;}}}

    // ---------- Passwords: pure JDK SHA-256 + salt, no new library ----------
    // New accounts store "sha256$<saltHex>$<hashHex>". Old demo accounts stored
    // plain text still log in (see authenticate), and are migrated to a hash
    // on their first successful sign-in, so no existing account breaks.
    static String sha256Hex(byte[] salt,String password)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");md.update(salt);md.update(password.getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(md.digest());}
    static String encodePassword(String password)throws Exception{byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);return "sha256$"+HexFormat.of().formatHex(salt)+"$"+sha256Hex(salt,password);}
    static boolean checkPassword(String input,String stored){if(input==null||stored==null)return false;if(stored.startsWith("sha256$")){String[] parts=stored.split("\\$",-1);if(parts.length!=3)return false;try{byte[] salt=HexFormat.of().parseHex(parts[1]);String expected=sha256Hex(salt,input);return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),parts[2].getBytes(StandardCharsets.UTF_8));}catch(Exception e){return false;}}return stored.equals(input);}
    static User authenticate(String username,String password)throws Exception{
        try(PreparedStatement q=db.prepareStatement("SELECT id,name,role,password FROM users WHERE LOWER(username)=LOWER(?)")){q.setString(1,username);try(ResultSet r=q.executeQuery()){
            if(!r.next())return null;
            String stored=r.getString(4);
            if(!checkPassword(password,stored))return null;
            try(PreparedStatement lk=db.prepareStatement("SELECT is_locked FROM users WHERE id=?")){lk.setInt(1,r.getInt(1));try(ResultSet lr=lk.executeQuery()){if(lr.next()&&lr.getInt(1)==1)return null;}}
            int id=r.getInt(1);String name=r.getString(2),role=r.getString(3);
            if(!stored.startsWith("sha256$")){try(PreparedStatement u=db.prepareStatement("UPDATE users SET password=? WHERE id=?")){u.setString(1,encodePassword(password));u.setInt(2,id);u.executeUpdate();}}
            return new User(id,name,role);
        }}
    }
    static void validateSignUp(String fullName,String username,String password,String confirm,String phone){
        if(fullName==null||fullName.trim().isEmpty())throw new IllegalArgumentException("Please enter your full name.");
        if(username==null||username.trim().isEmpty())throw new IllegalArgumentException("Please enter a username.");
        String u=username.trim();
        if(u.length()<3)throw new IllegalArgumentException("Username must be at least 3 characters.");
        if(u.length()>30)throw new IllegalArgumentException("Username must be at most 30 characters.");
        if(!u.matches("[A-Za-z0-9_.-]+"))throw new IllegalArgumentException("Username can only contain letters, numbers, underscore, dot or hyphen (no spaces).");
        if(password==null||password.length()<6)throw new IllegalArgumentException("Password must be at least 6 characters.");
        if(!password.equals(confirm))throw new IllegalArgumentException("Password and confirmation do not match.");
        if(phone!=null&&!phone.trim().isEmpty()){
            String ph=phone.trim();
            if(!ph.matches("[0-9+\\- ]+"))throw new IllegalArgumentException("Phone can only contain digits, +, hyphen or space.");
            if(ph.replaceAll("[^0-9]","").length()<7)throw new IllegalArgumentException("Please enter a valid phone number (at least 7 digits), or leave it blank.");
        }
    }
    /** Sign up a new CUSTOMER account. Returns the new user id. Sign-up never creates Admin or Receptionist accounts: those are created only by an Admin in Staff management, so a visitor cannot give themselves staff rights. */
    static int signUpCustomer(String fullName,String username,String password,String confirm,String phone)throws Exception{
        validateSignUp(fullName,username,password,confirm,phone);
        String u=username.trim();
        try(PreparedStatement q=db.prepareStatement("SELECT COUNT(*) FROM users WHERE LOWER(username)=LOWER(?)")){q.setString(1,u);try(ResultSet r=q.executeQuery()){if(r.next()&&r.getInt(1)>0)throw new IllegalArgumentException("Could not create an account with that username. Please try signing in with it, or choose a different username.");}}
        try(PreparedStatement ins=db.prepareStatement("INSERT INTO users(name,username,password,role,phone) VALUES(?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
            ins.setString(1,fullName.trim());ins.setString(2,u);ins.setString(3,encodePassword(password));ins.setString(4,"CUSTOMER");ins.setString(5,phone==null?"":phone.trim());ins.executeUpdate();
            try(ResultSet keys=ins.getGeneratedKeys()){if(keys.next())return keys.getInt(1);}
        }
        try(PreparedStatement q=db.prepareStatement("SELECT id FROM users WHERE LOWER(username)=LOWER(?)")){q.setString(1,u);try(ResultSet r=q.executeQuery()){if(r.next())return r.getInt(1);}}
        throw new Exception("Account was not created. Please try again.");
    }
    static void err(Component c,Exception e){JOptionPane.showMessageDialog(c,"Error: "+e.getMessage(),"Error",JOptionPane.ERROR_MESSAGE);}
    static void info(Component c,String s){JOptionPane.showMessageDialog(c,s,"Notice",JOptionPane.INFORMATION_MESSAGE);}
    static void mainLater(){SwingUtilities.invokeLater(()->{try{initDb();new Login().setVisible(true);}catch(Exception e){err(null,e);}});}
    public static void main(String[] args){try{UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}catch(Exception ignored){}mainLater();}

    static class Login extends JFrame{
        Login(){setTitle("HotelFlow · Sign in");setSize(480,500);setLocationRelativeTo(null);setDefaultCloseOperation(EXIT_ON_CLOSE);getContentPane().setBackground(BG);
            JPanel box=new JPanel();box.setBackground(Color.WHITE);box.setBorder(new EmptyBorder(34,42,30,42));box.setLayout(new BoxLayout(box,BoxLayout.Y_AXIS));
            JLabel logo=new JLabel("✦  HOTELFLOW");logo.setFont(new Font("SansSerif",Font.BOLD,25));logo.setForeground(TEAL);logo.setAlignmentX(.5f);
            JLabel sub=new JLabel("Hotel management system");sub.setForeground(Color.GRAY);sub.setAlignmentX(.5f);
            JTextField name=new JTextField();JPasswordField pass=new JPasswordField();JButton go=button("Sign in",TEAL);JButton signup=button("Sign up",NAVY);
            box.add(logo);box.add(Box.createVerticalStrut(8));box.add(sub);box.add(Box.createVerticalStrut(26));box.add(label("Username"));box.add(name);box.add(Box.createVerticalStrut(13));box.add(label("Password"));box.add(pass);box.add(Box.createVerticalStrut(22));box.add(go);box.add(Box.createVerticalStrut(10));JLabel noAccount=new JLabel("Don't have an account?");noAccount.setFont(new Font("SansSerif",Font.PLAIN,11));noAccount.setForeground(Color.GRAY);noAccount.setAlignmentX(.5f);box.add(noAccount);box.add(Box.createVerticalStrut(6));box.add(signup);
            JLabel demo=new JLabel("New here? Use Sign up below to create a customer account.");demo.setFont(new Font("SansSerif",Font.PLAIN,11));demo.setForeground(Color.GRAY);demo.setAlignmentX(.5f);box.add(Box.createVerticalStrut(18));box.add(demo);add(box);
            go.addActionListener(e->{try{String uname=name.getText().trim();if(!uname.isEmpty()&&isLocked(uname)){info(this,"This account is locked. Please contact an administrator.");return;}User u=authenticate(uname,new String(pass.getPassword()));if(u!=null){user=u;dispose();new Dashboard().setVisible(true);}else info(this,"Username or password is incorrect.");}catch(Exception ex){err(this,ex);}});
            signup.addActionListener(e->signUpDialog(name,pass));
            getRootPane().setDefaultButton(go);
        }
        void signUpDialog(JTextField loginName,JPasswordField loginPass){
            JDialog d=new JDialog(this,"Sign up",true);d.setSize(480,560);d.setLocationRelativeTo(this);
            JPanel box=new JPanel();box.setBackground(Color.WHITE);box.setBorder(new EmptyBorder(26,36,24,36));box.setLayout(new BoxLayout(box,BoxLayout.Y_AXIS));
            JLabel title=new JLabel("Create a customer account");title.setFont(new Font("SansSerif",Font.BOLD,19));title.setForeground(TEAL);title.setAlignmentX(.5f);
            JLabel note=new JLabel("New accounts are customers only. Staff accounts are created by an Admin.");note.setFont(new Font("SansSerif",Font.PLAIN,11));note.setForeground(Color.GRAY);note.setAlignmentX(.5f);
            JTextField fullName=new JTextField(),username=new JTextField(),phone=new JTextField();JPasswordField pw=new JPasswordField(),confirm=new JPasswordField();
            JButton create=button("Create account",TEAL),cancel=button("Cancel",new Color(72,88,106));
            box.add(title);box.add(Box.createVerticalStrut(6));box.add(note);box.add(Box.createVerticalStrut(18));
            box.add(label("Full name"));box.add(fullName);box.add(Box.createVerticalStrut(10));
            box.add(label("Username"));box.add(username);box.add(Box.createVerticalStrut(10));
            box.add(label("Password (at least 6 characters)"));box.add(pw);box.add(Box.createVerticalStrut(10));
            box.add(label("Confirm password"));box.add(confirm);box.add(Box.createVerticalStrut(10));
            box.add(label("Phone (optional)"));box.add(phone);box.add(Box.createVerticalStrut(20));
            box.add(create);box.add(Box.createVerticalStrut(8));box.add(cancel);
            d.add(box);d.getRootPane().setDefaultButton(create);
            create.addActionListener(e->{try{int id=signUpCustomer(fullName.getText(),username.getText(),new String(pw.getPassword()),new String(confirm.getPassword()),phone.getText());info(d,"Account created successfully! You can now sign in as "+username.getText().trim()+".");d.dispose();loginName.setText(username.getText().trim());loginPass.setText("");loginPass.requestFocusInWindow();}catch(IllegalArgumentException ex){info(d,ex.getMessage());}catch(Exception ex){info(d,"Account could not be created. Please try again.");}});
            cancel.addActionListener(e->d.dispose());
            d.setVisible(true);
        }
    }
    static JLabel label(String s){JLabel l=new JLabel(s);l.setForeground(INK);l.setFont(new Font("SansSerif",Font.BOLD,13));l.setAlignmentX(Component.LEFT_ALIGNMENT);return l;}
    static JButton button(String s,Color c){JButton b=new JButton(s);styleButton(b,c,Color.WHITE);b.setFont(new Font("SansSerif",Font.BOLD,12));b.setBorder(new EmptyBorder(10,16,10,16));return b;}
    static void styleButton(JButton b,Color background,Color foreground){b.setUI(new javax.swing.plaf.basic.BasicButtonUI());b.setBackground(background);b.setForeground(foreground);b.setOpaque(true);b.setContentAreaFilled(true);b.setBorderPainted(false);b.setFocusPainted(false);}
    static class Dashboard extends JFrame{
        JPanel content=new JPanel(new BorderLayout());
        Dashboard(){setTitle("HotelFlow · Hotel management");setMinimumSize(new Dimension(1000,680));setSize(1200,780);setLocationRelativeTo(null);setDefaultCloseOperation(EXIT_ON_CLOSE);setLayout(new BorderLayout());
            JPanel side=new JPanel();side.setBackground(NAVY);side.setPreferredSize(new Dimension(220,0));side.setLayout(new BoxLayout(side,BoxLayout.Y_AXIS));side.setBorder(new EmptyBorder(22,14,18,14));
            JLabel logo=new JLabel("✦  HOTELFLOW");logo.setForeground(Color.WHITE);logo.setFont(new Font("SansSerif",Font.BOLD,20));side.add(logo);side.add(Box.createVerticalStrut(32));
            JLabel who=new JLabel("  "+user.name()+" · "+roleName(user.role()));who.setForeground(new Color(183,201,215));who.setFont(new Font("SansSerif",Font.PLAIN,12));side.add(who);side.add(Box.createVerticalStrut(20));
            addNav(side,"Dashboard",this::home);
            if(user.role().equals("CUSTOMER")){
                addSection(side,"Booking");
                addNav(side,"Find a room",()->rooms(true));addNav(side,"My bookings",this::myBookings);addNav(side,"Services",this::customerServicesPage);addNav(side,"Reviews",this::review);
                addSection(side,"Account & help");
                addNav(side,"My account",this::myAccountPage);addNav(side,"Notifications",this::notificationsPage);addNav(side,"Support",this::supportPage);
            }
            if(user.role().equals("ADMIN")||user.role().equals("RECEPTIONIST")){
                addSection(side,"Shift");
                addNav(side,"Shift dashboard",this::shiftDashboard);addNav(side,"Shift handover",this::handoverPage);
                addSection(side,"Daily operations");
                addNav(side,"Rooms",()->rooms(false));addNav(side,"Bookings & stays",this::bookings);addNav(side,"Guests staying now",this::inHouseGuests);addNav(side,"Services & requests",this::staffServicesPage);addNav(side,"Payments",this::paymentsPage);addNav(side,"Housekeeping",this::housekeepingPage);addNav(side,"Customers",this::customersPage);addNav(side,"Support",this::supportPage);addNav(side,"Invoices",this::billing);addNav(side,"Export & backup",this::exportBackup);
                addSection(side,"Account");
                addNav(side,"My account",this::myAccountPage);addNav(side,"Notifications",this::notificationsPage);
            }
            if(user.role().equals("ADMIN")) {
                addSection(side,"Administration");
                addNav(side,"Prices & inventory",this::priceInventoryPage);addNav(side,"Room types",this::roomTypesPage);addNav(side,"Service catalog",this::servicesCatalogPage);addNav(side,"Staff",this::staff);addNav(side,"Reviews & replies",this::adminReviewsPage);addNav(side,"Reports",this::reportsPage);addNav(side,"Revenue",this::analytics);addNav(side,"System configuration",this::systemConfigPage);
            }
            side.add(Box.createVerticalGlue());JButton logout=button("Sign out",new Color(72,88,106));logout.setAlignmentX(Component.LEFT_ALIGNMENT);logout.addActionListener(e->{dispose();new Login().setVisible(true);});side.add(logout);
            JScrollPane sideScroll=new JScrollPane(side,JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);sideScroll.setPreferredSize(new Dimension(220,0));sideScroll.setBorder(null);
            add(sideScroll,BorderLayout.WEST);content.setBackground(BG);content.setBorder(new EmptyBorder(27,30,28,30));add(content,BorderLayout.CENTER);home();
        }
        void addNav(JPanel p,String name,Runnable action){JButton b=new JButton(name);styleButton(b,NAVY,Color.WHITE);b.setMaximumSize(new Dimension(205,42));b.setAlignmentX(Component.LEFT_ALIGNMENT);b.setHorizontalAlignment(SwingConstants.LEFT);b.setFont(new Font("SansSerif",Font.PLAIN,14));b.setBorder(new EmptyBorder(11,12,11,8));b.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseEntered(java.awt.event.MouseEvent e){b.setBackground(new Color(39,61,83));}public void mouseExited(java.awt.event.MouseEvent e){b.setBackground(NAVY);}});b.addActionListener(e->action.run());p.add(b);}
        void page(String title,String subtitle){content.removeAll();JPanel head=new JPanel();head.setOpaque(false);head.setLayout(new BoxLayout(head,BoxLayout.Y_AXIS));JLabel t=new JLabel(title);t.setFont(new Font("SansSerif",Font.BOLD,26));t.setForeground(INK);JLabel st=new JLabel(subtitle);st.setForeground(Color.GRAY);head.add(t);head.add(Box.createVerticalStrut(5));head.add(st);head.add(Box.createVerticalStrut(22));content.add(head,BorderLayout.NORTH);}
        void home(){page("Welcome, "+user.name(),"Hotel activity today · "+LocalDate.now());JPanel body=new JPanel(new BorderLayout(0,18));body.setOpaque(false);JPanel cards=new JPanel(new GridLayout(1,3,14,0));cards.setOpaque(false);try{cards.add(card("TOTAL ROOMS",String.valueOf(scalar("SELECT COUNT(*) FROM rooms")),"Rooms in the system"));cards.add(card("OCCUPIED ROOMS",String.valueOf(scalar("SELECT COUNT(*) FROM rooms WHERE status='Occupied'")),"Rooms currently occupied"));if(user.role().equals("CUSTOMER")){cards.add(card("MY BOOKINGS",String.valueOf(scalar("SELECT COUNT(*) FROM bookings WHERE guest_id="+user.id())),"Your bookings"));}else{cards.add(card("REVENUE",money(scalarD("SELECT COALESCE(SUM(total),0) FROM bookings WHERE status='Checked out'")),"Recorded revenue"));} }catch(Exception e){err(this,e);}body.add(cards,BorderLayout.NORTH);
            JPanel bottom=new JPanel(new BorderLayout());bottom.setOpaque(false);boolean customer=user.role().equals("CUSTOMER");JLabel recent=new JLabel(customer?"Your recent bookings":"Recent bookings");recent.setFont(new Font("SansSerif",Font.BOLD,17));
            JPanel topLabels=new JPanel();topLabels.setOpaque(false);topLabels.setLayout(new BoxLayout(topLabels,BoxLayout.Y_AXIS));
            if(customer){String nextText="No upcoming bookings — find a room to book your next stay.";try(PreparedStatement q=db.prepareStatement("SELECT b.booking_code,r.number,b.checkin,b.checkout FROM bookings b JOIN rooms r ON r.id=b.room_id WHERE b.guest_id=? AND b.status='Reserved' AND b.checkout>=? ORDER BY b.checkin LIMIT 1")){q.setInt(1,user.id());q.setString(2,LocalDate.now().toString());try(ResultSet r=q.executeQuery()){if(r.next()){long nights=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(r.getString(3)),LocalDate.parse(r.getString(4)));nextText="Next stay: "+r.getString(1)+" · Room "+r.getString(2)+" · "+r.getString(3)+" to "+r.getString(4)+" ("+nights+" nights).";}}}catch(Exception ignored){}JLabel next=new JLabel(nextText);next.setForeground(new java.awt.Color(15,121,122));next.setFont(new Font("SansSerif",Font.BOLD,14));topLabels.add(next);topLabels.add(Box.createVerticalStrut(10));}
            topLabels.add(recent);bottom.add(topLabels,BorderLayout.NORTH);try{String sql="SELECT b.id 'ID',u.name 'Customer',r.number 'Room',b.checkin 'Check-in',b.checkout 'Check-out',b.status 'Status' FROM bookings b JOIN users u ON u.id=b.guest_id JOIN rooms r ON r.id=b.room_id"+(customer?" WHERE b.guest_id="+user.id():"")+" ORDER BY b.id DESC LIMIT 8";bottom.add(tablePanel(new JTable(query(sql))),BorderLayout.CENTER);}catch(Exception e){err(this,e);}body.add(bottom,BorderLayout.CENTER);content.add(body,BorderLayout.CENTER);refresh();}
        JPanel card(String title,String val,String caption){JPanel p=new JPanel();p.setBackground(Color.WHITE);p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(230,234,239)),new EmptyBorder(16,18,16,18)));p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));JLabel a=new JLabel(title);a.setForeground(Color.GRAY);a.setFont(new Font("SansSerif",Font.BOLD,11));JLabel b=new JLabel(val);b.setFont(new Font("SansSerif",Font.BOLD,22));b.setForeground(TEAL);JLabel c=new JLabel(caption);c.setForeground(Color.GRAY);p.add(a);p.add(Box.createVerticalStrut(10));p.add(b);p.add(Box.createVerticalStrut(6));p.add(c);return p;}
        void rooms(boolean customer){
            if(customer){
                page("Find a room","Search by dates, guests, price, room type, capacity and amenities. Only rooms that are actually free for your dates are bookable — a room that is Occupied today can still be free for a future stay.");
                try{
                    JTextField inF=new JTextField(LocalDate.now().toString(),12),outF=new JTextField(LocalDate.now().plusDays(1).toString(),12),guestsF=new JTextField("2",4),minPriceF=new JTextField(10),maxPriceF=new JTextField(10),minCapF=new JTextField(10),amenF=new JTextField(14);
                    JComboBox<String> typeBox=new JComboBox<>();typeBox.addItem("All types");
                    try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT DISTINCT type FROM rooms ORDER BY type")){while(r.next())typeBox.addItem(r.getString(1));}
                    JCheckBox onlyAvail=new JCheckBox("Only rooms free for these dates",true);
                    JTable t=new JTable();t.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
                    Runnable reload=()->{try{
                        LocalDate in=parseDate(inF.getText()),out=parseDate(outF.getText());if(in==null||out==null||!out.isAfter(in)){info(this,"Please enter valid check-in / check-out dates (YYYY-MM-DD), with check-out after check-in.");return;}
                        int guestsNeed=1;try{guestsNeed=Integer.parseInt(guestsF.getText().trim());}catch(Exception ignored){}
                        double minP=0,maxP=Double.MAX_VALUE;int minCap=0;
                        try{if(!minPriceF.getText().trim().isEmpty())minP=Double.parseDouble(minPriceF.getText().trim());}catch(Exception ignored){}
                        try{if(!maxPriceF.getText().trim().isEmpty())maxP=Double.parseDouble(maxPriceF.getText().trim());}catch(Exception ignored){}
                        try{if(!minCapF.getText().trim().isEmpty())minCap=Integer.parseInt(minCapF.getText().trim());}catch(Exception ignored){}
                        String typeSel=(String)typeBox.getSelectedItem();String amenNeed=amenF.getText().trim().toLowerCase();
                        DefaultTableModel m=new DefaultTableModel(){public boolean isCellEditable(int r,int c){return false;}};
                        m.addColumn("ID");m.addColumn("Room number");m.addColumn("Type");m.addColumn("Price / night (for your dates)");m.addColumn("Capacity");m.addColumn("Amenities");m.addColumn("Status");m.addColumn("Free for your dates");
                        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,number,type,price,capacity,amenities,status,is_active FROM rooms ORDER BY number")){while(r.next()){
                            int rid=r.getInt(1);String type=r.getString(3);int cap=r.getInt(5);String amen=r.getString(6)==null?"":r.getString(6);
                            if(!"All types".equals(typeSel)&&!typeSel.equals(type))continue;
                            if(cap<minCap)continue;
                            if(!amenNeed.isEmpty()&&!amen.toLowerCase().contains(amenNeed))continue;
                            if(r.getInt(8)==0)continue;
                            double avg=roomAmount(rid,in,out)/Math.max(1,ChronoUnit.DAYS.between(in,out));
                            if(avg<minP||avg>maxP)continue;
                            boolean free=isRoomAvailable(rid,in,out,-1);
                            if(onlyAvail.isSelected()&&!free)continue;
                            m.addRow(new Object[]{rid,r.getString(2),type,money(avg),cap,amen,r.getString(7),free?"Yes":"No"});
                        }}
                        t.setModel(m);
                    }catch(Exception ex){err(this,ex);}};
                    JButton findBtn=button("Search",NAVY),detailBtn=button("View room details",NAVY),bookBtn=button("Book selected rooms",TEAL);
                    findBtn.addActionListener(e->reload.run());
                    detailBtn.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a room first.");return;}int rid=Integer.parseInt(t.getValueAt(row,0).toString());roomDetailDialog(rid,parseDate(inF.getText()),parseDate(outF.getText()));});
                    bookBtn.addActionListener(e->{try{
                        int[] rows=t.getSelectedRows();if(rows.length==0){info(this,"Please select at least one room first.");return;}
                        LocalDate in=parseDate(inF.getText()),out=parseDate(outF.getText());if(in==null||out==null||!out.isAfter(in)){info(this,"Please enter valid dates first.");return;}
                        int guestsNeed=2;try{guestsNeed=Integer.parseInt(guestsF.getText().trim());}catch(Exception ignored){}
                        java.util.List<Integer> roomIds=new java.util.ArrayList<>();
                        for(int row:rows){int rid=Integer.parseInt(t.getValueAt(row,0).toString());if(!"Yes".equals(t.getValueAt(row,7).toString())){info(this,"Room "+t.getValueAt(row,1)+" is not free for these dates. Please unselect it.");return;}roomIds.add(rid);}
                        bookMultipleRooms(roomIds,in,out,guestsNeed);
                    }catch(Exception ex){err(this,ex);}});
                    JPanel filters=new JPanel(new FlowLayout(FlowLayout.LEFT));filters.setOpaque(false);
                    filters.add(label("Check-in"));filters.add(inF);filters.add(label("Check-out"));filters.add(outF);filters.add(label("Guests"));filters.add(guestsF);filters.add(label("Type"));filters.add(typeBox);
                    JPanel filters2=new JPanel(new FlowLayout(FlowLayout.LEFT));filters2.setOpaque(false);
                    filters2.add(label("Min price"));filters2.add(minPriceF);filters2.add(label("Max price"));filters2.add(maxPriceF);filters2.add(label("Min capacity"));filters2.add(minCapF);filters2.add(label("Amenities contain"));filters2.add(amenF);filters2.add(onlyAvail);filters2.add(findBtn);filters2.add(detailBtn);filters2.add(bookBtn);
                    JPanel north=new JPanel(new BorderLayout(0,6));north.setOpaque(false);north.add(filters,BorderLayout.NORTH);north.add(filters2,BorderLayout.SOUTH);
                    JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(north,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);
                    JLabel hint=new JLabel("Tip: select one room to book it, or select several rooms (Ctrl + click) to book them together for the same dates. Prices above already include seasonal / weekend rules for your dates.");hint.setForeground(Color.GRAY);
                    holder.add(hint,BorderLayout.SOUTH);content.add(holder,BorderLayout.CENTER);
                    reload.run();
                }catch(Exception e){err(this,e);}refresh();return;
            }
            page("Room management","Manage room types, rates, and occupancy status. Admin can add / edit rooms; Receptionist can view all rooms here.");
            try{JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));actions.setOpaque(false);if(user.role().equals("ADMIN")){JButton add=button("＋ Add room",TEAL);add.addActionListener(e->roomDialog(null));actions.add(add);}
                JTable t=new JTable(query("SELECT id 'ID',number 'Room number',type 'Type',price 'Base rate / night',capacity 'Capacity',amenities 'Amenities',status 'Status',is_active 'On sale' FROM rooms ORDER BY number"));
                if(user.role().equals("ADMIN")){JButton edit=button("Edit selected room",NAVY);edit.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a room first.");return;}int rid=Integer.parseInt(t.getValueAt(row,0).toString());try{roomDialog(loadRoom(rid));}catch(Exception ex){err(this,ex);}});actions.add(edit);}
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(actions,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        Room loadRoom(int roomId)throws Exception{try(PreparedStatement q=db.prepareStatement("SELECT id,number,type,price,status,capacity,amenities,description,image_path,is_active FROM rooms WHERE id=?")){q.setInt(1,roomId);try(ResultSet r=q.executeQuery()){if(!r.next())return null;return new Room(r.getInt(1),r.getString(2),r.getString(3),r.getDouble(4),r.getString(5),r.getInt(6),r.getString(7)==null?"":r.getString(7),r.getString(8)==null?"":r.getString(8),r.getString(9)==null?"":r.getString(9),r.getInt(10));}}}
        void roomDetailDialog(int roomId,LocalDate in,LocalDate out){try{
            Room room=loadRoom(roomId);if(room==null){info(this,"Room not found.");return;}
            StringBuilder sb=new StringBuilder();
            sb.append("Room ").append(room.number()).append(" · ").append(room.type()).append("\n");
            sb.append("Description: ").append(room.description().isEmpty()?"(No description yet)":room.description()).append("\n");
            sb.append("Base rate: ").append(money(room.price())).append(" / night\n");
            sb.append("Capacity: ").append(room.capacity()).append(" guests\n");
            sb.append("Amenities: ").append(room.amenities()).append("\n");
            if(!room.imagePath().isEmpty())sb.append("Image: ").append(room.imagePath()).append("\n");
            sb.append("Status: ").append(room.status()).append(room.active()==1?" (on sale)":" (sale closed)").append("\n");
            sb.append("Hotel check-in: ").append(setting("checkin_time","14:00")).append(" · Check-out: ").append(setting("checkout_time","12:00")).append("\n");
            sb.append("Cancellation policy: ").append(setting("cancellation_policy","")).append("\n");
            if(in!=null&&out!=null&&out.isAfter(in)){long nights=ChronoUnit.DAYS.between(in,out);sb.append("\nFor ").append(in).append(" to ").append(out).append(" (").append(nights).append(" nights): room charge ").append(money(roomAmount(roomId,in,out))).append(" (seasonal / weekend prices already applied, tax ").append(settingDouble("tax_rate",0)).append("% not yet included).");}
            JTextArea a=new JTextArea(sb.toString(),16,55);a.setEditable(false);a.setLineWrap(true);
            JPanel panel=new JPanel(new BorderLayout(8,8));panel.add(new JScrollPane(a),BorderLayout.CENTER);
            try(PreparedStatement q=db.prepareStatement("SELECT image_blob FROM rooms WHERE id=?")){q.setInt(1,roomId);try(ResultSet r=q.executeQuery()){if(r.next()){byte[] blob=r.getBytes(1);if(blob!=null&&blob.length>0){try{java.awt.Image img=new javax.swing.ImageIcon(blob).getImage();if(img.getWidth(null)>0){java.awt.Image scaled=img.getScaledInstance(320,-1,java.awt.Image.SCALE_SMOOTH);JLabel pic=new JLabel(new javax.swing.ImageIcon(scaled));pic.setBorder(javax.swing.BorderFactory.createTitledBorder("Room photo (stored in database)"));panel.add(pic,BorderLayout.SOUTH);}else{panel.add(new JLabel("A stored image exists but could not be displayed."),BorderLayout.SOUTH);}}catch(Exception ignored){panel.add(new JLabel("A stored image exists but could not be displayed."),BorderLayout.SOUTH);}}}}}
            JOptionPane.showMessageDialog(this,panel,"Room details",JOptionPane.INFORMATION_MESSAGE);
        }catch(Exception e){err(this,e);}}
        void bookMultipleRooms(java.util.List<Integer> roomIds,LocalDate in,LocalDate out,int guestsNeed){try{
            JTextField guestsF=new JTextField(String.valueOf(guestsNeed)),occupantsF=new JTextField(),promoF=new JTextField();JTextArea specialA=new JTextArea(3,40);specialA.setLineWrap(true);
            Object[] f={"Number of guests (per room)",guestsF,"Occupant names (optional)",occupantsF,"Special requests (optional)",new JScrollPane(specialA),"Promotion code (optional)",promoF};
            if(JOptionPane.showConfirmDialog(this,f,"Book "+roomIds.size()+" room(s) — guest details",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            int guests=Integer.parseInt(guestsF.getText().trim());if(guests<=0)throw new IllegalArgumentException("Number of guests must be greater than 0.");
            StringBuilder breakdown=new StringBuilder();double totalRoom=0,totalTax=0,totalAll=0;
            for(int rid:roomIds){
                double roomAmt=roomAmount(rid,in,out);String type=roomTypeOf(rid);double promoDisc=promoDiscount(promoF.getText().trim(),type,in);double discAmt=roomAmt*promoDisc/100;double tax=(roomAmt-discAmt)*settingDouble("tax_rate",0)/100;double tot=roomAmt-discAmt+tax;
                breakdown.append("Room ").append(roomNumberOf(rid)).append(" (").append(type).append("): room ").append(money(roomAmt)).append(" · promo ").append(promoDisc).append("% (-").append(money(discAmt)).append(") · tax ").append(money(tax)).append(" · total ").append(money(tot)).append("\n");
                totalRoom+=roomAmt;totalTax+=tax;totalAll+=tot;
            }
            breakdown.append("\nStay: ").append(in).append(" to ").append(out).append(" (").append(ChronoUnit.DAYS.between(in,out)).append(" nights) · Guests per room: ").append(guests);
            breakdown.append("\nTOTAL for all rooms: ").append(money(totalAll)).append(" (room ").append(money(totalRoom)).append(" + tax ").append(money(totalTax)).append(")");
            breakdown.append("\n\nAfter confirming, you will receive a booking code for each room. You can pay a deposit or the full amount from My bookings → Pay.");
            if(JOptionPane.showConfirmDialog(this,breakdown.toString(),"Confirm booking?",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            StringBuilder codes=new StringBuilder();
            for(int rid:roomIds){
                String type=roomTypeOf(rid);double promoDisc=promoDiscount(promoF.getText().trim(),type,in);
                int bid=createBookingRecord(user.id(),rid,in,out,guests,occupantsF.getText().trim(),specialA.getText().trim(),promoF.getText().trim(),promoDisc);
                String code="";try(PreparedStatement q=db.prepareStatement("SELECT booking_code FROM bookings WHERE id=?")){q.setInt(1,bid);try(ResultSet r=q.executeQuery()){if(r.next())code=r.getString(1);}}
                codes.append(code).append(" (Room ").append(roomNumberOf(rid)).append(")\n");
            }
            info(this,"Booking confirmed!\n"+codes.toString()+"\nTotal: "+money(totalAll)+"\nGo to My bookings to pay and view details.");
            myBookings();
        }catch(Exception e){err(this,e);}}
        void roomDialog(Room room){
            JTextField number=new JTextField(room==null?"":room.number()),price=new JTextField(room==null?"550000":String.valueOf(room.price())),capacity=new JTextField(room==null?"2":String.valueOf(room.capacity())),amenities=new JTextField(room==null?"Wi-Fi, TV, Điều hòa":room.amenities()),description=new JTextField(room==null?"":room.description()),image=new JTextField(room==null?"":room.imagePath());
            JComboBox<String> type=new JComboBox<>();type.setEditable(true);
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT name FROM room_types WHERE active=1 ORDER BY name")){boolean any=false;while(r.next()){type.addItem(r.getString(1));any=true;}if(!any){type.addItem("Standard");type.addItem("Deluxe");type.addItem("VIP");}}catch(Exception ignored){type.addItem("Standard");type.addItem("Deluxe");type.addItem("VIP");}
            final byte[][] newImage=new byte[1][];JLabel imageLabel=new JLabel(room==null?"No image stored in the database yet.":"An image is stored in the database for this room (choose a new file to replace it).");JCheckBox removeImage=new JCheckBox("Remove the stored image");
            JButton chooseImage=button("Choose image file…",NAVY);chooseImage.addActionListener(e->{try{JFileChooser fc=new JFileChooser();fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Image files (PNG, JPG, GIF)","png","jpg","jpeg","gif"));if(fc.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;java.io.File f=fc.getSelectedFile();byte[] data=java.nio.file.Files.readAllBytes(f.toPath());if(data.length>5*1024*1024){info(this,"Image is larger than 5 MB and was not selected. Please choose a smaller image.");return;}newImage[0]=data;imageLabel.setText("Selected: "+f.getName()+" ("+(data.length/1024)+" KB) — will be stored in the database on save.");}catch(Exception ex){err(this,ex);}});
            JComboBox<String> status=new JComboBox<>(new String[]{"Available","Occupied","Cleaning","Maintenance","Closed"});
            JCheckBox active=new JCheckBox("On sale (customers can book this room)",room==null||room.active()==1);
            if(room!=null){type.setSelectedItem(room.type());status.setSelectedItem(room.status());}
            Object[] fields={"Room number",number,"Room type (from Room types — you can also type a new type)",type,"Base rate / night (VND)",price,"Status",status,"Capacity (guests)",capacity,"Amenities (comma separated)",amenities,"Description",description,"Image path or URL (optional, shown in room details)",image,"Stored image (saved inside hotel.db, shown in room details)",imageLabel,chooseImage,removeImage,active};
            if(JOptionPane.showConfirmDialog(this,fields,room==null?"Add room":"Update room",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            try{
                int cap=Integer.parseInt(capacity.getText().trim());if(cap<=0)throw new IllegalArgumentException("Capacity must be greater than 0.");
                if(room==null){try(PreparedStatement p=db.prepareStatement("INSERT INTO rooms(number,type,price,status,capacity,amenities,description,image_path,is_active) VALUES(?,?,?,?,?,?,?,?,?)")){p.setString(1,number.getText().trim());p.setString(2,((String)type.getSelectedItem()).trim());p.setDouble(3,Double.parseDouble(price.getText()));p.setString(4,(String)status.getSelectedItem());p.setInt(5,cap);p.setString(6,amenities.getText());p.setString(7,description.getText());p.setString(8,image.getText());p.setInt(9,active.isSelected()?1:0);p.executeUpdate();}}
                else{try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET number=?,type=?,price=?,status=?,capacity=?,amenities=?,description=?,image_path=?,is_active=? WHERE id=?")){p.setString(1,number.getText().trim());p.setString(2,((String)type.getSelectedItem()).trim());p.setDouble(3,Double.parseDouble(price.getText()));p.setString(4,(String)status.getSelectedItem());p.setInt(5,cap);p.setString(6,amenities.getText());p.setString(7,description.getText());p.setString(8,image.getText());p.setInt(9,active.isSelected()?1:0);p.setInt(10,room.id());p.executeUpdate();}}
                String roomType=((String)type.getSelectedItem()).trim();int savedRoomId=room==null?-1:room.id();
                if(room==null){try(PreparedStatement q=db.prepareStatement("SELECT id FROM rooms WHERE number=?")){q.setString(1,number.getText().trim());try(ResultSet r=q.executeQuery()){if(r.next())savedRoomId=r.getInt(1);}}}
                try(PreparedStatement p=db.prepareStatement("INSERT OR IGNORE INTO room_types(name,base_price,capacity,amenities) VALUES(?,?,?,?)")){p.setString(1,roomType);p.setDouble(2,Double.parseDouble(price.getText()));p.setInt(3,cap);p.setString(4,amenities.getText());p.executeUpdate();}
                if(savedRoomId>0){if(removeImage.isSelected()){try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET image_blob=NULL WHERE id=?")){p.setInt(1,savedRoomId);p.executeUpdate();}}else if(newImage[0]!=null){try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET image_blob=? WHERE id=?")){p.setBytes(1,newImage[0]);p.setInt(2,savedRoomId);p.executeUpdate();}}}
                logAction(room==null?"Add room":"Update room","Room "+number.getText().trim());rooms(false);
            }catch(Exception e){err(this,e);}
        }
        void bookings(){
            page("Bookings & stays","Search by booking code, guest name, phone or date. Create walk-in bookings, check guests in, add services, check out, cancel or extend a stay.");
            try{
                JTable t=new JTable();
                JTextField search=new JTextField(16);search.setToolTipText("Search by booking code, guest name, phone, room or date (YYYY-MM-DD)");
                JComboBox<String> statusFilter=new JComboBox<>(new String[]{"All","Reserved","Occupied","Checked out","Cancelled","No-show"});
                JButton find=button("Search",NAVY);
                Runnable reload=()->{try{
                    String term=search.getText().trim().replace("'","''");String status=(String)statusFilter.getSelectedItem();
                    String sql="SELECT b.id 'ID',b.booking_code 'Code',u.name 'Guest',u.phone 'Phone',r.number 'Room',b.checkin 'Check-in',b.checkout 'Check-out',b.status 'Status',b.payment_status 'Payment',b.total 'Final total' FROM bookings b JOIN users u ON u.id=b.guest_id JOIN rooms r ON r.id=b.room_id WHERE (b.booking_code LIKE '%"+term+"%' OR u.name LIKE '%"+term+"%' OR u.phone LIKE '%"+term+"%' OR r.number LIKE '%"+term+"%' OR b.checkin LIKE '%"+term+"%' OR b.checkout LIKE '%"+term+"%')";
                    if(!"All".equals(status)) sql+=" AND b.status='"+status+"'";
                    sql+=" ORDER BY b.id DESC";t.setModel(query(sql));
                }catch(Exception ex){err(this,ex);}};
                reload.run();
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);
                JButton create=button("＋ New booking",TEAL),detail=button("View details",NAVY),edit=button("Edit booking",NAVY),ci=button("Check-in",NAVY),svc=button("Laundry / Minibar / Discount",NAVY),co=button("Check out & invoice",TEAL),cancel=button("Cancel booking",new Color(176,58,58)),extend=button("Extend stay",NAVY),changeRoom=button("Change room",NAVY),pay=button("Record payment",NAVY),noshow=button("Mark no-show",new Color(176,58,58));
                for(JButton b:new JButton[]{create,detail,edit,ci,svc,co,cancel,extend,changeRoom,pay,noshow})top.add(b);
                create.addActionListener(e->bookingDialog(-1));
                detail.addActionListener(e->{int id=selected(t);if(id>0)showBookingDetail(id);});
                edit.addActionListener(e->{int id=selected(t);if(id>0)editBookingDialog(id);});
                ci.addActionListener(e->changeBooking(t,"checkin"));
                svc.addActionListener(e->serviceDialog(t));
                co.addActionListener(e->checkout(t));
                cancel.addActionListener(e->cancelBooking(t));
                extend.addActionListener(e->extendBooking(t));
                changeRoom.addActionListener(e->changeRoomDialog(t));
                pay.addActionListener(e->{int id=selected(t);if(id>0)payDialog(id);});
                noshow.addActionListener(e->markNoShow(t));
                find.addActionListener(e->reload.run());search.addActionListener(e->reload.run());statusFilter.addActionListener(e->reload.run());
                JPanel filter=new JPanel(new FlowLayout(FlowLayout.LEFT));filter.setOpaque(false);filter.add(label("Search"));filter.add(search);filter.add(label("Status"));filter.add(statusFilter);filter.add(find);
                JPanel north=new JPanel(new BorderLayout(0,8));north.setOpaque(false);north.add(top,BorderLayout.NORTH);north.add(filter,BorderLayout.SOUTH);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(north,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void markNoShow(JTable t){int id=selected(t);if(id<0)return;try{
            String status="",checkin="";try(PreparedStatement q=db.prepareStatement("SELECT status,checkin FROM bookings WHERE id=?")){q.setInt(1,id);try(ResultSet r=q.executeQuery()){if(r.next()){status=r.getString(1);checkin=r.getString(2);}}}
            if(!"Reserved".equals(status)){info(this,"Only a Reserved booking can be marked no-show.");return;}
            if(LocalDate.now().isBefore(LocalDate.parse(checkin))){info(this,"This booking's check-in date has not passed yet, so it cannot be marked no-show.");return;}
            JTextField reason=new JTextField("Guest did not arrive");
            Object[] f={"No-show reason",reason,"Note: payments already made will be kept as the no-show fee (no refund), unless an Admin records a refund separately."};
            if(JOptionPane.showConfirmDialog(this,f,"Mark no-show",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            double paid=paidNet(id);
            try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET status='No-show',status_note=?,cancel_reason=?,cancel_fee=? WHERE id=?")){p.setString(1,reason.getText());p.setString(2,reason.getText());p.setDouble(3,paid);p.setInt(4,id);p.executeUpdate();}
            updatePaymentStatus(id);logAction("Mark no-show","Booking "+id+" fee kept "+money(paid));info(this,"Booking marked as no-show. Payments kept as no-show fee: "+money(paid)+".");bookings();
        }catch(Exception e){err(this,e);}}
        void cancelBooking(JTable t){
            int id=selected(t);if(id<0)return;
            try{
                String status="";try(PreparedStatement p=db.prepareStatement("SELECT status FROM bookings WHERE id=?")){p.setInt(1,id);try(ResultSet r=p.executeQuery()){if(r.next()) status=r.getString(1);}}
                if(status.isEmpty()){info(this,"Booking not found.");return;}
                if("Checked out".equals(status)||"Cancelled".equals(status)||"No-show".equals(status)){info(this,"This booking cannot be cancelled (it is already "+status+").");return;}
                if("Occupied".equals(status)&&!user.role().equals("ADMIN")){info(this,"Only an Admin can cancel a checked-in booking. A Receptionist can cancel Reserved bookings.");return;}
                double refund=refundForCancel(id,LocalDate.now());double paid=paidNet(id);
                JTextField reason=new JTextField();JTextField refundF=new JTextField(String.valueOf((long)refund));
                boolean admin=user.role().equals("ADMIN");
                Object[] f={"Reason for cancellation",reason,"Refund amount (VND) — policy suggests "+money(refund)+" from paid "+money(paid)+(admin?"\nAdmin may adjust this amount.": "\nReceptionist must use the policy amount.") ,refundF,"Cancellation policy: "+setting("cancellation_policy","")};
                if(JOptionPane.showConfirmDialog(this,f,"Cancel booking",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                double refundAmt=Double.parseDouble(refundF.getText().trim());
                if(!admin&&Math.abs(refundAmt-refund)>0.5){info(this,"Receptionist must use the policy refund amount ("+money(refund)+"). Ask an Admin to adjust it if needed.");return;}
                if(refundAmt<0||refundAmt>paid+0.5)throw new IllegalArgumentException("Refund must be between 0 and the amount paid.");
                int roomId=0;try(PreparedStatement q=db.prepareStatement("SELECT room_id FROM bookings WHERE id=?")){q.setInt(1,id);try(ResultSet r=q.executeQuery()){if(r.next())roomId=r.getInt(1);}}
                try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET status='Cancelled',cancel_reason=?,cancel_fee=? WHERE id=?")){p.setString(1,reason.getText());p.setDouble(2,paid-refundAmt);p.setInt(3,id);p.executeUpdate();}
                if(refundAmt>0.5){try(PreparedStatement p=db.prepareStatement("INSERT INTO payments(booking_id,method,type,amount,note,status) VALUES(?, 'Original method', 'Refund', ?, ?, 'Completed')")){p.setInt(1,id);p.setDouble(2,refundAmt);p.setString(3,"Refund for cancellation: "+reason.getText());p.executeUpdate();}}
                if("Occupied".equals(status)){try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET status='Cleaning' WHERE id=?")){p.setInt(1,roomId);p.executeUpdate();}}
                updatePaymentStatus(id);logAction("Cancel booking","Booking "+id+" refund "+money(refundAmt)+" reason: "+reason.getText());
                info(this,"Booking cancelled. Refund recorded: "+money(refundAmt)+".");bookings();
            }catch(Exception e){err(this,e);}
        }
        void changeBooking(JTable t,String action){int id=selected(t);if(id<0)return;try{
            int roomId,guestId;LocalDate in,out;String status,guestName,guestPhone;
            try(PreparedStatement q=db.prepareStatement("SELECT b.room_id,b.guest_id,b.checkin,b.checkout,b.status,u.name,u.phone FROM bookings b JOIN users u ON u.id=b.guest_id WHERE b.id=?")){q.setInt(1,id);try(ResultSet r=q.executeQuery()){if(!r.next()){info(this,"Booking not found.");return;}roomId=r.getInt(1);guestId=r.getInt(2);in=LocalDate.parse(r.getString(3));out=LocalDate.parse(r.getString(4));status=r.getString(5);guestName=r.getString(6);guestPhone=r.getString(7);}}
            if(!"Reserved".equals(status)){info(this,"Only a Reserved booking can be checked in.");return;}
            String code="";try(PreparedStatement q=db.prepareStatement("SELECT booking_code FROM bookings WHERE id=?")){q.setInt(1,id);try(ResultSet r=q.executeQuery()){if(r.next())code=r.getString(1);}}
            JCheckBox verified=new JCheckBox("I have verified the guest's identity (ID / passport checked)",false);
            JTextField occupantsF=new JTextField();JComboBox<String> rooms=new JComboBox<>();Map<String,Integer> roomIds=new HashMap<>();
            String curLabel="Keep current room "+roomNumberOf(roomId);rooms.addItem(curLabel);roomIds.put(curLabel,roomId);
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,number,type,price FROM rooms WHERE is_active=1 AND status NOT IN ('Maintenance','Closed') ORDER BY number")){while(r.next()){int rid=r.getInt(1);if(rid==roomId)continue;if(!isRoomAvailable(rid,in,out,id))continue;String n=r.getString(2)+" · "+r.getString(3)+" · "+money(r.getDouble(4))+"/night";rooms.addItem(n);roomIds.put(n,rid);}}
            Object[] f={"Booking "+code+"\nGuest: "+guestName+" · Phone: "+guestPhone+"\nStay: "+in+" to "+out,verified,"Occupant names (optional)",occupantsF,"Room (keep current, or change if the original room is not ready)",rooms};
            if(JOptionPane.showConfirmDialog(this,f,"Check-in — verify identity first",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            if(!verified.isSelected()){info(this,"Check-in requires identity verification. Please tick the verification box and try again.");return;}
            int newRoom=roomIds.get(rooms.getSelectedItem());
            try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET status='Occupied',room_id=?,occupants=?,identity_verified=1 WHERE id=? AND status='Reserved'")){p.setInt(1,newRoom);p.setString(2,occupantsF.getText());p.setInt(3,id);if(p.executeUpdate()==0){info(this,"This booking is not eligible for check-in.");return;}}
            try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET status='Occupied' WHERE id=?")){p.setInt(1,newRoom);p.executeUpdate();}
            logAction("Check-in","Booking "+code+" room "+roomNumberOf(newRoom));try{notifyGuest(id,"Check-in","Check-in completed "+code,"Welcome! You are checked in to room "+roomNumberOf(newRoom)+".");}catch(Exception ignored){}info(this,"Check-in completed. Room "+roomNumberOf(newRoom)+" is now Occupied.");bookings();
        }catch(Exception e){err(this,e);}}
        void bookingDialog(int roomId){try{
            JComboBox<String> guests=new JComboBox<>();Map<String,Integer> guestIds=new HashMap<>();
            if(user.role().equals("CUSTOMER")){String n=user.name()+" (#"+user.id()+")";guests.addItem(n);guestIds.put(n,user.id());}
            else{try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,name FROM users WHERE role='CUSTOMER' ORDER BY name")){while(r.next()){String n=r.getString(2)+" (#"+r.getInt(1)+")";guests.addItem(n);guestIds.put(n,r.getInt(1));}}guests.addItem("Walk-in new guest (create a customer account)");guestIds.put("Walk-in new guest (create a customer account)",-1);}
            JComboBox<String> rooms=new JComboBox<>();Map<String,Integer> roomIds=new HashMap<>();
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,number,type,price FROM rooms WHERE is_active=1 AND status NOT IN ('Maintenance','Closed') ORDER BY number")){while(r.next()){String n=r.getString(2)+" · "+r.getString(3)+" · "+money(r.getDouble(4))+"/night (base)";rooms.addItem(n);roomIds.put(n,r.getInt(1));}}
            if(roomId>0)for(String n:roomIds.keySet())if(roomIds.get(n)==roomId)rooms.setSelectedItem(n);
            JTextField in=new JTextField(LocalDate.now().toString()),out=new JTextField(LocalDate.now().plusDays(1).toString()),guestsCount=new JTextField("2"),occupants=new JTextField(),promo=new JTextField();JTextArea special=new JTextArea(3,40);special.setLineWrap(true);
            Object[] f={"Customer",guests,"Room (base price shown; seasonal / weekend prices apply per night)",rooms,"Check-in date (YYYY-MM-DD)",in,"Check-out date (YYYY-MM-DD)",out,"Number of guests",guestsCount,"Occupant names (optional)",occupants,"Special requests (optional)",new JScrollPane(special),"Promotion code (optional)",promo};
            if(JOptionPane.showConfirmDialog(this,f,"Create booking",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            if(guests.getSelectedItem()==null||rooms.getSelectedItem()==null){info(this,"At least one guest and one available room are required.");return;}
            LocalDate a=parseDate(in.getText()),b=parseDate(out.getText());if(a==null||b==null||!b.isAfter(a))throw new IllegalArgumentException("Check-out date must be after check-in date.");
            int guestId;if(guestIds.get(guests.getSelectedItem())==-1){
                JTextField wName=new JTextField(),wPhone=new JTextField(),wEmail=new JTextField();
                Object[] wf={"Walk-in guest full name",wName,"Phone",wPhone,"Email (optional)",wEmail};
                if(JOptionPane.showConfirmDialog(this,wf,"Create walk-in customer account",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                if(wName.getText().trim().isEmpty())throw new IllegalArgumentException("Walk-in guest name is required.");
                String uname="guest_"+wPhone.getText().replaceAll("[^0-9]","");if(uname.equals("guest_"))uname="guest_"+System.currentTimeMillis()%100000;
                int tries=0;while(true){try(PreparedStatement p=db.prepareStatement("INSERT INTO users(name,username,password,role,phone,email) VALUES(?,?,?,?,?,?)")){p.setString(1,wName.getText().trim());p.setString(2,uname);p.setString(3,encodePassword("123456"));p.setString(4,"CUSTOMER");p.setString(5,wPhone.getText().trim());p.setString(6,wEmail.getText().trim());p.executeUpdate();break;}catch(SQLException sq){if(tries++>5)throw sq;uname=uname+"_"+tries;}}
                try(PreparedStatement q=db.prepareStatement("SELECT id FROM users WHERE username=?")){q.setString(1,uname);try(ResultSet r=q.executeQuery()){if(!r.next())throw new Exception("Walk-in account was not created.");guestId=r.getInt(1);}}
                info(this,"Walk-in account created: username "+uname+", temporary password 123456. Please ask the guest to change it in My account.");
            }else guestId=guestIds.get(guests.getSelectedItem());
            int selectedRoom=roomIds.get(rooms.getSelectedItem());int gCount=Integer.parseInt(guestsCount.getText().trim());if(gCount<=0)throw new IllegalArgumentException("Number of guests must be greater than 0.");
            String type=roomTypeOf(selectedRoom);double promoDisc=promoDiscount(promo.getText().trim(),type,a);double roomAmt=roomAmount(selectedRoom,a,b);double discAmt=roomAmt*promoDisc/100;double tax=(roomAmt-discAmt)*settingDouble("tax_rate",0)/100;double total=roomAmt-discAmt+tax;
            String breakdown="Room "+roomNumberOf(selectedRoom)+" ("+type+") · "+a+" to "+b+" ("+ChronoUnit.DAYS.between(a,b)+" nights)\nRoom charge (seasonal / weekend prices applied): "+money(roomAmt)+"\nPromotion "+promo.getText().trim()+" ("+promoDisc+"%): -"+money(discAmt)+"\nTax ("+settingDouble("tax_rate",0)+"%): "+money(tax)+"\nTOTAL estimate (before services): "+money(total)+"\n\nServices added during the stay will be added later. A deposit of "+settingDouble("deposit_percent",30)+"% is "+money(total*settingDouble("deposit_percent",30)/100)+".";
            if(JOptionPane.showConfirmDialog(this,breakdown+"\n\nConfirm this booking?","Confirm booking",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            int bid=createBookingRecord(guestId,selectedRoom,a,b,gCount,occupants.getText().trim(),special.getText().trim(),promo.getText().trim(),promoDisc);
            String code="";try(PreparedStatement q=db.prepareStatement("SELECT booking_code FROM bookings WHERE id=?")){q.setInt(1,bid);try(ResultSet r=q.executeQuery()){if(r.next())code=r.getString(1);}}
            if(JOptionPane.showConfirmDialog(this,"Booking confirmed! Code: "+code+"\nTotal estimate: "+money(total)+"\n\nDo you want to record a payment now (deposit / full)?","Booking confirmed",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)payDialog(bid);
            if(user.role().equals("CUSTOMER"))myBookings();else bookings();
        }catch(Exception e){err(this,e);}}
        int selected(JTable t){if(t.getSelectedRow()<0){info(this,"Please select a booking first.");return -1;}return Integer.parseInt(t.getValueAt(t.getSelectedRow(),0).toString());}
        void extendBooking(JTable t){
            int id=selected(t);if(id<0)return;
            JTextField daysField=new JTextField("1");
            Object[] f={"Extend by how many nights?",daysField};
            if(JOptionPane.showConfirmDialog(this,f,"Extend stay",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            try{
                int extra=Integer.parseInt(daysField.getText().trim());
                if(extra<=0)throw new IllegalArgumentException("Number of nights must be greater than 0.");
                int roomId;LocalDate oldOut;String status;
                try(PreparedStatement p=db.prepareStatement("SELECT room_id,checkout,status FROM bookings WHERE id=?")){p.setInt(1,id);try(ResultSet r=p.executeQuery()){if(!r.next())throw new Exception("Booking not found.");roomId=r.getInt(1);oldOut=LocalDate.parse(r.getString(2));status=r.getString(3);}}
                if(!"Reserved".equals(status)&&!"Occupied".equals(status)){info(this,"Only a Reserved or Occupied booking can be extended.");return;}
                LocalDate newOut=oldOut.plusDays(extra);
                try(PreparedStatement p=db.prepareStatement("SELECT COUNT(*) FROM bookings WHERE room_id=? AND id<>? AND status IN ('Reserved','Occupied') AND checkin < ? AND checkout > ?")){p.setInt(1,roomId);p.setInt(2,id);p.setString(3,newOut.toString());p.setString(4,oldOut.toString());try(ResultSet r=p.executeQuery()){if(r.next()&&r.getInt(1)>0)throw new IllegalArgumentException("The room is already booked for the extended dates.");}}
                try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET checkout=? WHERE id=?")){p.setString(1,newOut.toString());p.setInt(2,id);p.executeUpdate();}
                logAction("Extend stay","Booking "+id+" extended to "+newOut);
                info(this,"Stay extended to "+newOut+". The new total will be calculated at check-out (seasonal / weekend prices apply).");
            }catch(Exception e){err(this,e);}
        }
        void serviceDialog(JTable t){int id=selected(t);if(id<0)return;JTextField laundry=new JTextField("0"),mini=new JTextField("0"),discount=new JTextField("0");Object[] f={"Laundry charge (VND)",laundry,"Minibar / food (VND)",mini,"Discount (%) — Receptionist max 20%, Admin up to 100%",discount};if(JOptionPane.showConfirmDialog(this,f,"Additional services & discount",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try{double disc=Double.parseDouble(discount.getText());if(disc<0||disc>100)throw new IllegalArgumentException("Discount must be between 0 and 100.");if(!user.role().equals("ADMIN")&&disc>20)throw new IllegalArgumentException("Discount over 20% requires an Admin.");try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET laundry=?,minibar=?,discount=? WHERE id=? AND status='Occupied'")){p.setDouble(1,Double.parseDouble(laundry.getText()));p.setDouble(2,Double.parseDouble(mini.getText()));p.setDouble(3,disc);p.setInt(4,id);if(p.executeUpdate()==0)info(this,"Services can only be added for checked-in guests.");else bookings();}}catch(Exception e){err(this,e);}}
        String invoice(int id)throws Exception{try(PreparedStatement p=db.prepareStatement("SELECT b.booking_code,u.name,u.phone,u.email,r.number,r.type,b.checkin,COALESCE(b.actual_out,b.checkout),b.laundry,b.minibar,b.total,b.discount,b.promo_discount,b.promo_code,b.status,b.payment_status FROM bookings b JOIN users u ON u.id=b.guest_id JOIN rooms r ON r.id=b.room_id WHERE b.id=?")){p.setInt(1,id);try(ResultSet r=p.executeQuery()){if(!r.next())throw new Exception("Booking not found.");long days=Math.max(1,ChronoUnit.DAYS.between(LocalDate.parse(r.getString(7)),LocalDate.parse(r.getString(8))));int roomId=0;try(PreparedStatement q=db.prepareStatement("SELECT room_id FROM bookings WHERE id=?")){q.setInt(1,id);try(ResultSet rr=q.executeQuery()){if(rr.next())roomId=rr.getInt(1);}}double roomCost=roomAmount(roomId,LocalDate.parse(r.getString(7)),LocalDate.parse(r.getString(8)));double laundry=r.getDouble(9),mini=r.getDouble(10);double catalogSvc=confirmedServicesAmount(id);double subtotal=roomCost+laundry+mini+catalogSvc;double discPct=r.getDouble(12)+r.getDouble(13);double discAmt=subtotal*discPct/100;double tax=(subtotal-discAmt)*settingDouble("tax_rate",0)/100;double total="Checked out".equals(r.getString(15))&&r.getDouble(11)>0?r.getDouble(11):subtotal-discAmt+tax;double paid=paidNet(id);
            StringBuilder sb=new StringBuilder();
            sb.append("HOTELFLOW · INVOICE\nBooking code: ").append(r.getString(1)).append("\nCustomer: ").append(r.getString(2)).append("\nPhone: ").append(r.getString(3)).append(" · Email: ").append(r.getString(4)==null?"":r.getString(4));
            sb.append("\nRoom: ").append(r.getString(5)).append(" (").append(r.getString(6)).append(")\nStay: ").append(r.getString(7)).append(" — ").append(r.getString(8)).append(" (").append(days).append(" nights)");
            sb.append("\nRoom charge (seasonal / weekend prices applied): ").append(money(roomCost));
            sb.append("\nCatalog services confirmed: ").append(money(catalogSvc));
            sb.append("\nLaundry: ").append(money(laundry)).append("\nMinibar / food: ").append(money(mini));
            sb.append("\nSubtotal: ").append(money(subtotal));
            sb.append("\nDiscount (").append(discPct).append("%");
            if(!r.getString(14).isEmpty())sb.append(", promo ").append(r.getString(14));
            sb.append("): -").append(money(discAmt));
            sb.append("\nTax (").append(settingDouble("tax_rate",0)).append("%): ").append(money(tax));
            sb.append("\nTOTAL: ").append(money(total));
            sb.append("\nPaid: ").append(money(paid)).append(" · Balance due: ").append(money(total-paid));
            sb.append("\nBooking status: ").append(r.getString(15)).append(" · Payment status: ").append(r.getString(16));
            sb.append("\n\nConfirmed services detail:\n");
            try(Statement s=db.createStatement();ResultSet rs=s.executeQuery("SELECT service_name,quantity,amount FROM booking_services WHERE booking_id="+id+" AND status='Confirmed' ORDER BY id")){boolean any=false;while(rs.next()){any=true;sb.append("  ").append(rs.getString(1)).append(" x ").append(rs.getInt(2)).append(" = ").append(money(rs.getDouble(3))).append("\n");}if(!any)sb.append("  (none)\n");}
            sb.append("Thank you for staying with us!");
            return sb.toString();}}}
        void checkout(JTable t){int id=selected(t);if(id<0)return;try{
            String status="";try(PreparedStatement q=db.prepareStatement("SELECT status FROM bookings WHERE id=?")){q.setInt(1,id);try(ResultSet r=q.executeQuery()){if(r.next())status=r.getString(1);}}
            if(!"Occupied".equals(status)){info(this,"The guest must check in before checking out.");return;}
            double total=billTotal(id);double paid=paidNet(id);double balance=total-paid;
            String bill=invoice(id);
            JComboBox<String> method=new JComboBox<>(new String[]{"Cash","Card","Transfer"});JTextField amount=new JTextField(balance>0?String.valueOf((long)Math.ceil(balance)):"0");
            String prompt=balance>0.5?"\n\nBalance due: "+money(balance)+". Record the payment now (method and amount below), or cancel to collect it later from Payments.":"\n\nNo balance due."+(balance<-0.5?" Overpaid by "+money(-balance)+" — a refund will be recorded.":"");
            Object[] f={bill+prompt,"Payment method (if collecting now)",method,"Amount to collect now (VND, 0 = collect later)",amount};
            if(JOptionPane.showConfirmDialog(this,f,"Check-out — confirm total and collect balance",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            double collect=0;try{collect=Double.parseDouble(amount.getText().trim());}catch(Exception ignored){}
            if(collect>0){
                if(collect>balance+0.5&&balance>0)throw new IllegalArgumentException("Amount to collect cannot exceed the balance due.");
                try(PreparedStatement p=db.prepareStatement("INSERT INTO payments(booking_id,method,type,amount,note,status) VALUES(?,?,?,?,'Check-out collection','Completed')")){p.setInt(1,id);p.setString(2,(String)method.getSelectedItem());p.setString(3,collect>=balance-0.5?"Full":"Partial");p.setDouble(4,collect);p.executeUpdate();}
            }
            if(balance<-0.5){
                double refund=-balance;
                try(PreparedStatement p=db.prepareStatement("INSERT INTO payments(booking_id,method,type,amount,note,status) VALUES(?,?, 'Refund', ?, 'Overpayment refund at check-out','Completed')")){p.setInt(1,id);p.setString(2,(String)method.getSelectedItem());p.setDouble(3,refund);p.executeUpdate();}
            }
            try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET status='Checked out',actual_out=?,total=?,tax_amount=?,room_amount=? WHERE id=? AND status='Occupied'")){
                String now=LocalDate.now().toString();p.setString(1,now);
                int roomId=0;LocalDate in=null,out=null;try(PreparedStatement q=db.prepareStatement("SELECT room_id,checkin,checkout FROM bookings WHERE id=?")){q.setInt(1,id);try(ResultSet r=q.executeQuery()){if(r.next()){roomId=r.getInt(1);in=LocalDate.parse(r.getString(2));out=LocalDate.parse(r.getString(3));}}}
                double sub=billSubtotal(id);double disc=sub*billDiscountPct(id)/100;double tax=(sub-disc)*settingDouble("tax_rate",0)/100;
                p.setDouble(2,sub-disc+tax);p.setDouble(3,tax);p.setDouble(4,roomAmount(roomId,in,LocalDate.parse(now)));p.setInt(5,id);
                if(p.executeUpdate()==0){info(this,"The guest must check in before checking out.");return;}
            }
            try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET status='Cleaning' WHERE id=(SELECT room_id FROM bookings WHERE id=?)")){p.setInt(1,id);p.executeUpdate();}
            updatePaymentStatus(id);logAction("Check-out","Booking "+id+" total "+money(total)+" paid "+money(paidNet(id)));try{notifyGuest(id,"Check-out","Check-out completed","Your stay is closed. Final total "+money(total)+", paid "+money(paidNet(id))+". Thank you, and please leave a review from My bookings / Reviews.");}catch(Exception ignored){}
            info(this,"Check-out completed. The room is now marked for cleaning.");billing();
        }catch(Exception e){err(this,e);}}
        void billing(){page("Invoices","Invoices for completed stays, with payment status and balance due. Export an invoice as PDF or text.");
            try{
                JComboBox<String> filter=new JComboBox<>(new String[]{"Checked out","All","Balance due"});
                JTable t=new JTable();
                Runnable reload=()->{try{
                    String sel=(String)filter.getSelectedItem();
                    String sql="SELECT b.id 'ID',b.booking_code 'Code',u.name 'Customer',r.number 'Room',b.checkin 'Check-in',COALESCE(b.actual_out,b.checkout) 'Check-out',b.total 'Total', (SELECT COALESCE(SUM(CASE WHEN type='Refund' THEN -amount ELSE amount END),0) FROM payments WHERE booking_id=b.id AND status='Completed') 'Paid', b.total - (SELECT COALESCE(SUM(CASE WHEN type='Refund' THEN -amount ELSE amount END),0) FROM payments WHERE booking_id=b.id AND status='Completed') 'Balance', b.payment_status 'Payment status', b.status 'Status' FROM bookings b JOIN users u ON u.id=b.guest_id JOIN rooms r ON r.id=b.room_id";
                    if("Checked out".equals(sel))sql+=" WHERE b.status='Checked out'";
                    else if("Balance due".equals(sel))sql+=" WHERE b.status='Checked out' AND b.total > (SELECT COALESCE(SUM(CASE WHEN type='Refund' THEN -amount ELSE amount END),0) FROM payments WHERE booking_id=b.id AND status='Completed')";
                    sql+=" ORDER BY b.id DESC";t.setModel(query(sql));
                }catch(Exception ex){err(this,ex);}};
                reload.run();filter.addActionListener(e->reload.run());
                JButton txt=button("Export TXT",NAVY),pdf=button("Export PDF",TEAL),payBtn=button("Record payment",NAVY),detailBtn=button("View details",NAVY),emailBtn=button("Email invoice",NAVY);
                txt.addActionListener(e->export(t,false));pdf.addActionListener(e->export(t,true));
                payBtn.addActionListener(e->{int id=selected(t);if(id>0)payDialog(id);});
                detailBtn.addActionListener(e->{int id=selected(t);if(id>0)showBookingDetail(id);});
                emailBtn.addActionListener(e->{int id=selected(t);if(id>0)emailBooking(id,true);});
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);top.add(label("Show"));top.add(filter);top.add(txt);top.add(pdf);top.add(payBtn);top.add(detailBtn);top.add(emailBtn);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();}
        void export(JTable t,boolean pdf){int id=selected(t);if(id<0)return;try{String text=invoice(id);JFileChooser fc=new JFileChooser();fc.setSelectedFile(new File("Invoice_"+id+(pdf?".pdf":".txt")));if(fc.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;if(!pdf)Files.writeString(fc.getSelectedFile().toPath(),text,StandardCharsets.UTF_8);else try(PDDocument doc=new PDDocument()){PDPage page=new PDPage();doc.addPage(page);try(PDPageContentStream cs=new PDPageContentStream(doc,page)){cs.beginText();cs.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER),12);cs.newLineAtOffset(55,740);cs.setLeading(19);for(String line:text.split("\n")){String ascii=Normalizer.normalize(line.replace("đ","d").replace("Đ","D"),Normalizer.Form.NFD).replaceAll("\\p{M}","").replaceAll("[^\\x20-\\x7E]","?");cs.showText(ascii);cs.newLine();}cs.endText();}doc.save(fc.getSelectedFile());}info(this,"Invoice exported: "+fc.getSelectedFile());}catch(Exception e){err(this,e);}}
        void myBookings(){
            page("My bookings","Your upcoming bookings and your stay history. Select a booking to view details, change it, cancel it, or pay for it.");
            try{
                JComboBox<String> filter=new JComboBox<>(new String[]{"All","Upcoming","History"});
                JTable t=new JTable();
                Runnable reload=()->{try{
                    String sel=(String)filter.getSelectedItem();String today=LocalDate.now().toString();
                    String sql="SELECT b.id 'ID',b.booking_code 'Code',r.number 'Room',r.type 'Type',b.checkin 'Check-in',b.checkout 'Check-out',b.guests 'Guests',b.status 'Status',b.payment_status 'Payment',b.total 'Total (checked-out)' FROM bookings b JOIN rooms r ON r.id=b.room_id WHERE b.guest_id="+user.id();
                    if("Upcoming".equals(sel))sql+=" AND b.status IN ('Reserved','Occupied') AND b.checkout >= '"+today+"'";
                    else if("History".equals(sel))sql+=" AND (b.status IN ('Checked out','Cancelled','No-show') OR b.checkout < '"+today+"')";
                    sql+=" ORDER BY b.id DESC";t.setModel(query(sql));
                }catch(Exception ex){err(this,ex);}};
                reload.run();filter.addActionListener(e->reload.run());
                JButton detail=button("View details",NAVY),change=button("Change dates / guest info",NAVY),cancel=button("Cancel booking",new Color(176,58,58)),pay=button("Pay",TEAL),receipt=button("View receipt / invoice",NAVY),exportInv=button("Export invoice TXT",NAVY),emailBtn=button("Email confirmation",NAVY);
                detail.addActionListener(e->{int id=selected(t);if(id>0)showBookingDetail(id);});
                change.addActionListener(e->changeMyBooking(t));
                cancel.addActionListener(e->cancelMyBooking(t));
                pay.addActionListener(e->{int id=selected(t);if(id>0)payDialog(id);});
                receipt.addActionListener(e->{int id=selected(t);if(id>0)showBookingDetail(id);});
                exportInv.addActionListener(e->{int id=selected(t);if(id<=0)return;try{String st="";try(PreparedStatement q=db.prepareStatement("SELECT status FROM bookings WHERE id=?")){q.setInt(1,id);try(ResultSet r=q.executeQuery()){if(r.next())st=r.getString(1);}}if(!"Checked out".equals(st)){info(this,"The invoice file is available after check-out. You can view the booking details now.");showBookingDetail(id);return;}saveTextFile("Invoice_"+id+".txt",invoice(id));}catch(Exception ex){err(this,ex);}});
                emailBtn.addActionListener(e->{int id=selected(t);if(id>0)emailBooking(id,false);});
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);top.add(label("Show"));top.add(filter);for(JButton b:new JButton[]{detail,change,cancel,pay,receipt,exportInv,emailBtn})top.add(b);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void changeMyBooking(JTable t){int id=selected(t);if(id<0)return;try{
            int roomId,guests;LocalDate in,out;String status,occupants,special;
            try(PreparedStatement q=db.prepareStatement("SELECT room_id,guests,checkin,checkout,status,occupants,special_requests FROM bookings WHERE id=? AND guest_id=?")){q.setInt(1,id);q.setInt(2,user.id());try(ResultSet r=q.executeQuery()){if(!r.next()){info(this,"Booking not found.");return;}roomId=r.getInt(1);guests=r.getInt(2);in=LocalDate.parse(r.getString(3));out=LocalDate.parse(r.getString(4));status=r.getString(5);occupants=r.getString(6)==null?"":r.getString(6);special=r.getString(7)==null?"":r.getString(7);}}
            if("Checked out".equals(status)||"Cancelled".equals(status)||"No-show".equals(status)){info(this,"This booking can no longer be changed (status: "+status+").");return;}
            JTextField inF=new JTextField(in.toString()),outF=new JTextField(out.toString()),guestsF=new JTextField(String.valueOf(guests)),occF=new JTextField(occupants);JTextArea specA=new JTextArea(special,3,40);specA.setLineWrap(true);
            boolean canChangeDates="Reserved".equals(status)&&in.isAfter(LocalDate.now());
            Object[] f={"Check-in date (YYYY-MM-DD)"+(canChangeDates?"":" — locked for checked-in stays"),inF,"Check-out date (YYYY-MM-DD)"+(canChangeDates?"":" — locked for checked-in stays"),outF,"Number of guests",guestsF,"Occupant names",occF,"Special requests",new JScrollPane(specA)};
            if(JOptionPane.showConfirmDialog(this,f,"Change booking",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            LocalDate newIn=in,newOut=out;
            if(canChangeDates){newIn=parseDate(inF.getText());newOut=parseDate(outF.getText());if(newIn==null||newOut==null||!newOut.isAfter(newIn))throw new IllegalArgumentException("Check-out date must be after check-in date.");if(!isRoomAvailable(roomId,newIn,newOut,id))throw new IllegalArgumentException("The room is already booked for the new dates.");}
            int newGuests=Integer.parseInt(guestsF.getText().trim());if(newGuests<=0)throw new IllegalArgumentException("Number of guests must be greater than 0.");
            double newRoomAmt=roomAmount(roomId,newIn,newOut);double newTotal=newRoomAmt; // services and discounts are applied at check-out; show room estimate here
            if(JOptionPane.showConfirmDialog(this,"New stay: "+newIn+" to "+newOut+"\nNew room charge estimate: "+money(newRoomAmt)+"\nServices, discounts and tax will be recalculated at check-out.\nConfirm this change?","Confirm change",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET checkin=?,checkout=?,guests=?,occupants=?,special_requests=? WHERE id=?")){p.setString(1,newIn.toString());p.setString(2,newOut.toString());p.setInt(3,newGuests);p.setString(4,occF.getText());p.setString(5,specA.getText());p.setInt(6,id);p.executeUpdate();}
            logAction("Change booking","Customer changed booking "+id+" to "+newIn+" - "+newOut);info(this,"Booking updated.");myBookings();
        }catch(Exception e){err(this,e);}}
        void cancelMyBooking(JTable t){int id=selected(t);if(id<0)return;try{
            String status="";try(PreparedStatement q=db.prepareStatement("SELECT status FROM bookings WHERE id=? AND guest_id=?")){q.setInt(1,id);q.setInt(2,user.id());try(ResultSet r=q.executeQuery()){if(r.next())status=r.getString(1);}}
            if(!"Reserved".equals(status)){info(this,"Only a Reserved booking can be cancelled by the guest. If you are already checked in, please contact Receptionist.");return;}
            double refund=refundForCancel(id,LocalDate.now());double paid=paidNet(id);
            JTextField reason=new JTextField();
            Object[] f={"Reason for cancellation (optional)",reason,"Cancellation policy: "+setting("cancellation_policy","")+"\nPaid so far: "+money(paid)+"\nRefund you will receive: "+money(refund)};
            if(JOptionPane.showConfirmDialog(this,f,"Cancel booking",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET status='Cancelled',cancel_reason=?,cancel_fee=? WHERE id=?")){p.setString(1,reason.getText());p.setDouble(2,paid-refund);p.setInt(3,id);p.executeUpdate();}
            if(refund>0.5){try(PreparedStatement p=db.prepareStatement("INSERT INTO payments(booking_id,method,type,amount,note,status) VALUES(?, 'Original method', 'Refund', ?, ?, 'Completed')")){p.setInt(1,id);p.setDouble(2,refund);p.setString(3,"Refund for cancellation: "+reason.getText());p.executeUpdate();}}
            updatePaymentStatus(id);logAction("Cancel booking (customer)","Booking "+id+" refund "+money(refund));info(this,"Booking cancelled. Refund recorded: "+money(refund)+". You can track it in My bookings → View details.");myBookings();
        }catch(Exception e){err(this,e);}}
        void review(){
            page("Reviews","After a completed stay, rate the room and the service, write a comment, and see staff replies to your reviews.");
            try{
                JComboBox<String> stayBox=new JComboBox<>();Map<String,Integer> stayIds=new HashMap<>();stayBox.addItem("General (no specific stay)");stayIds.put("General (no specific stay)",-1);
                try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT b.id,b.booking_code,r.number FROM bookings b JOIN rooms r ON r.id=b.room_id WHERE b.guest_id="+user.id()+" AND b.status='Checked out' ORDER BY b.id DESC")){while(r.next()){String n=r.getString(2)+" · Room "+r.getString(3);stayBox.addItem(n);stayIds.put(n,r.getInt(1));}}
                JComboBox<Integer> roomStars=new JComboBox<>(new Integer[]{5,4,3,2,1}),svcStars=new JComboBox<>(new Integer[]{5,4,3,2,1});JTextArea comment=new JTextArea(4,50);comment.setLineWrap(true);
                JPanel form=new JPanel(new FlowLayout(FlowLayout.LEFT));form.setOpaque(false);form.add(label("Stay"));form.add(stayBox);form.add(label("Room rating"));form.add(roomStars);form.add(label("Service rating"));form.add(svcStars);
                JPanel form2=new JPanel(new BorderLayout(0,8));form2.setOpaque(false);form2.add(form,BorderLayout.NORTH);form2.add(new JScrollPane(comment),BorderLayout.CENTER);
                JButton send=button("Submit review",TEAL);
                send.addActionListener(e->{try{
                    if(comment.getText().trim().isEmpty())throw new IllegalArgumentException("Please write a comment.");
                    int bid=stayIds.get(stayBox.getSelectedItem());
                    try(PreparedStatement p=db.prepareStatement("INSERT INTO reviews(guest_id,booking_id,rating,service_rating,comment,status) VALUES(?,?,?,?,?,'Approved')")){p.setInt(1,user.id());if(bid<0)p.setNull(2,Types.INTEGER);else p.setInt(2,bid);p.setInt(3,(Integer)roomStars.getSelectedItem());p.setInt(4,(Integer)svcStars.getSelectedItem());p.setString(5,comment.getText().trim());p.executeUpdate();}
                    logAction("Submit review","Guest "+user.id()+" rated room "+roomStars.getSelectedItem()+" service "+svcStars.getSelectedItem());info(this,"Thank you for your feedback!");review();
                }catch(Exception ex){err(this,ex);}});
                form2.add(send,BorderLayout.SOUTH);
                JTable mine=new JTable(query("SELECT rv.id 'ID',COALESCE(b.booking_code,'General') 'Stay',rv.rating 'Room rating',COALESCE(rv.service_rating,0) 'Service rating',rv.comment 'Comment',rv.status 'Status',COALESCE(rv.reply,'') 'Staff reply' FROM reviews rv LEFT JOIN bookings b ON b.id=rv.booking_id WHERE rv.guest_id="+user.id()+" ORDER BY rv.id DESC"));
                JPanel holder=new JPanel(new BorderLayout(0,14));holder.setOpaque(false);holder.add(form2,BorderLayout.NORTH);holder.add(new JLabel("My reviews"),BorderLayout.CENTER);holder.add(tablePanel(mine),BorderLayout.SOUTH);
                content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void staff(){page("Staff management","Create accounts, change roles, lock / unlock accounts, and assign shifts. Only an Admin can change another user's role or lock an account — and never their own account, to avoid locking themselves out.");
            try{
                JTable t=new JTable(query("SELECT id 'ID',name 'Full name',username 'Username',role 'Role',phone 'Phone',COALESCE(email,'') 'Email',is_locked 'Locked (1=yes)' FROM users ORDER BY role,name"));
                JButton add=button("＋ Create account",TEAL),roleBtn=button("Change role",NAVY),lockBtn=button("Lock / unlock",NAVY),shiftBtn=button("Assign shift",TEAL),shiftsBtn=button("View shifts",NAVY);
                add.addActionListener(e->staffDialog());
                roleBtn.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a user first.");return;}int uid=Integer.parseInt(t.getValueAt(row,0).toString());if(uid==user.id()){info(this,"You cannot change your own role.");return;}JComboBox<String> roleBox=new JComboBox<>(new String[]{"ADMIN","RECEPTIONIST","CUSTOMER"});if(JOptionPane.showConfirmDialog(this,roleBox,"New role for "+t.getValueAt(row,1),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try(PreparedStatement p=db.prepareStatement("UPDATE users SET role=? WHERE id=?")){p.setString(1,(String)roleBox.getSelectedItem());p.setInt(2,uid);p.executeUpdate();}catch(Exception ex){err(this,ex);}logAction("Change role","User "+uid+" -> "+roleBox.getSelectedItem());staff();});
                lockBtn.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a user first.");return;}int uid=Integer.parseInt(t.getValueAt(row,0).toString());if(uid==user.id()){info(this,"You cannot lock your own account.");return;}try(PreparedStatement p=db.prepareStatement("UPDATE users SET is_locked=CASE WHEN is_locked=1 THEN 0 ELSE 1 END WHERE id=?")){p.setInt(1,uid);p.executeUpdate();}catch(Exception ex){err(this,ex);}logAction("Lock / unlock account","User "+uid);staff();});
                shiftBtn.addActionListener(e->{try{
                    JComboBox<String> staffBox=new JComboBox<>();Map<String,Integer> ids=new HashMap<>();
                    try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,name FROM users WHERE role IN ('ADMIN','RECEPTIONIST') ORDER BY name")){while(r.next()){String n=r.getString(2)+" (#"+r.getInt(1)+")";staffBox.addItem(n);ids.put(n,r.getInt(1));}}
                    JTextField dateF=new JTextField(LocalDate.now().toString());JComboBox<String> shiftBox=new JComboBox<>(new String[]{"Morning","Afternoon","Night"});JTextField note=new JTextField();
                    Object[] f={"Staff",staffBox,"Shift date (YYYY-MM-DD)",dateF,"Shift",shiftBox,"Note",note};
                    if(JOptionPane.showConfirmDialog(this,f,"Assign shift",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                    try(PreparedStatement p=db.prepareStatement("INSERT INTO staff_shifts(user_id,shift_date,shift_name,note) VALUES(?,?,?,?)")){p.setInt(1,ids.get(staffBox.getSelectedItem()));p.setString(2,dateF.getText().trim());p.setString(3,(String)shiftBox.getSelectedItem());p.setString(4,note.getText());p.executeUpdate();}
                    logAction("Assign shift",staffBox.getSelectedItem()+" "+dateF.getText()+" "+shiftBox.getSelectedItem());try{createNotification(ids.get(staffBox.getSelectedItem()),null,"Shift","Shift assigned: "+shiftBox.getSelectedItem()+" on "+dateF.getText(),note.getText());}catch(Exception ignored){}info(this,"Shift assigned.");
                }catch(Exception ex){err(this,ex);}});
                shiftsBtn.addActionListener(e->{try{JTable st=new JTable(query("SELECT s.id 'ID',u.name 'Staff',s.shift_date 'Date',s.shift_name 'Shift',s.note 'Note' FROM staff_shifts s JOIN users u ON u.id=s.user_id ORDER BY s.shift_date DESC, s.id DESC LIMIT 100"));JTextArea a=new JTextArea("Shifts are shown in the table below. Close this dialog to return.",2,50);a.setEditable(false);JOptionPane.showMessageDialog(this,new JScrollPane(st),"Shifts",JOptionPane.INFORMATION_MESSAGE);}catch(Exception ex){err(this,ex);}});
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);for(JButton b:new JButton[]{add,roleBtn,lockBtn,shiftBtn,shiftsBtn})top.add(b);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();}
        void staffDialog(){JTextField name=new JTextField(),username=new JTextField(),phone=new JTextField(),email=new JTextField();JPasswordField pass=new JPasswordField();JComboBox<String> role=new JComboBox<>(new String[]{"ADMIN","RECEPTIONIST","CUSTOMER"});Object[] f={"Full name",name,"Username",username,"Password",pass,"Role",role,"Phone",phone,"Email",email};if(JOptionPane.showConfirmDialog(this,f,"Create account",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try{String u=username.getText().trim();if(u.isEmpty()){info(this,"Please enter a username.");return;}try(PreparedStatement q=db.prepareStatement("SELECT COUNT(*) FROM users WHERE LOWER(username)=LOWER(?)")){q.setString(1,u);try(ResultSet r=q.executeQuery()){if(r.next()&&r.getInt(1)>0){info(this,"Username already exists. Please choose another username.");return;}}}try(PreparedStatement p=db.prepareStatement("INSERT INTO users(name,username,password,role,phone,email) VALUES(?,?,?,?,?,?)")){p.setString(1,name.getText().trim());p.setString(2,u);p.setString(3,encodePassword(new String(pass.getPassword())));p.setString(4,(String)role.getSelectedItem());p.setString(5,phone.getText());p.setString(6,email.getText().trim());p.executeUpdate();logAction("Create account","User "+u+" role "+role.getSelectedItem());staff();}}catch(Exception e){err(this,e);}}
        void analytics(){page("Revenue & reports","Revenue from checked-out bookings, grouped by day, month, or year.");JComboBox<String> period=new JComboBox<>(new String[]{"Day","Month","Year"});JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);top.add(label("Group by"));top.add(period);JPanel graph=new JPanel(new BorderLayout());graph.setBackground(Color.WHITE);graph.setBorder(new EmptyBorder(14,14,14,14));JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(graph,BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);Runnable draw=()->{try{String fmt=period.getSelectedIndex()==0?"%Y-%m-%d":period.getSelectedIndex()==1?"%Y-%m":"%Y";JTable data=new JTable(query("SELECT strftime('"+fmt+"',actual_out) 'Period',SUM(total) 'Revenue' FROM bookings WHERE status='Checked out' GROUP BY 1 ORDER BY 1 DESC LIMIT 12"));graph.removeAll();graph.add(new JScrollPane(data),BorderLayout.NORTH);graph.add(new RevenueChart(data),BorderLayout.CENTER);graph.revalidate();}catch(Exception e){err(this,e);}};period.addActionListener(e->draw.run());draw.run();refresh();}
        void cleaning(){housekeepingPage();}
        void exportBackup(){
            page("Export & backup","Export data as CSV for spreadsheets, or back up the whole database file. No new software is needed.");
            JPanel body=new JPanel();body.setOpaque(false);body.setLayout(new BoxLayout(body,BoxLayout.Y_AXIS));
            JButton csvBookings=button("Export bookings CSV",TEAL);csvBookings.addActionListener(e->exportBookingsCsv());
            JButton csvRooms=button("Export rooms CSV",NAVY);csvRooms.addActionListener(e->exportRoomsCsv());
            JButton backup=button("Back up hotel.db",NAVY);backup.addActionListener(e->backupDb());
            JLabel note=new JLabel("CSV files open in Excel / Google Sheets. The backup is a copy of hotel.db — keep it in a safe place.");note.setForeground(Color.GRAY);
            for(JButton b:new JButton[]{csvBookings,csvRooms,backup}){b.setAlignmentX(Component.LEFT_ALIGNMENT);body.add(b);body.add(Box.createVerticalStrut(12));}
            body.add(Box.createVerticalStrut(8));body.add(note);
            content.add(body,BorderLayout.NORTH);refresh();
        }
        static String csvCell(Object v){String s=v==null?"":v.toString();if(s.contains(",")||s.contains("\"")||s.contains("\n"))return "\""+s.replace("\"","\"\"")+"\"";return s;}
        void saveTextFile(String defaultName,String text){JFileChooser fc=new JFileChooser();fc.setSelectedFile(new File(defaultName));if(fc.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;try{Files.writeString(fc.getSelectedFile().toPath(),text,StandardCharsets.UTF_8);info(this,"Exported: "+fc.getSelectedFile());}catch(Exception e){err(this,e);}}
        void exportBookingsCsv(){try{StringBuilder sb=new StringBuilder("ID,Guest,Room,Check-in,Check-out,Status,Laundry,Minibar,Discount %,Total\n");try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT b.id,u.name,r.number,b.checkin,COALESCE(b.actual_out,b.checkout),b.status,b.laundry,b.minibar,b.discount,b.total FROM bookings b JOIN users u ON u.id=b.guest_id JOIN rooms r ON r.id=b.room_id ORDER BY b.id")){while(r.next()){for(int i=1;i<=10;i++){if(i>1)sb.append(",");sb.append(csvCell(r.getObject(i)));}sb.append("\n");}}saveTextFile("bookings.csv",sb.toString());}catch(Exception e){err(this,e);}}
        void exportRoomsCsv(){try{StringBuilder sb=new StringBuilder("ID,Room number,Type,Rate / night,Status\n");try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,number,type,price,status FROM rooms ORDER BY number")){while(r.next()){for(int i=1;i<=5;i++){if(i>1)sb.append(",");sb.append(csvCell(r.getObject(i)));}sb.append("\n");}}saveTextFile("rooms.csv",sb.toString());}catch(Exception e){err(this,e);}}
        void backupDb(){try{JFileChooser fc=new JFileChooser();fc.setSelectedFile(new File("hotel-backup-"+LocalDate.now()+".db"));if(fc.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Files.copy(new File(DB).toPath(),fc.getSelectedFile().toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);info(this,"Backup saved: "+fc.getSelectedFile());}catch(Exception e){err(this,e);}}
        void addSection(JPanel p,String text){JLabel s=new JLabel("  "+text);s.setForeground(new Color(140,165,185));s.setFont(new Font("SansSerif",Font.BOLD,11));s.setAlignmentX(Component.LEFT_ALIGNMENT);p.add(Box.createVerticalStrut(14));p.add(s);p.add(Box.createVerticalStrut(6));}
        String bookingDetailText(int bookingId)throws Exception{
            try(PreparedStatement q=db.prepareStatement("SELECT b.booking_code,u.name,u.phone,u.email,r.number,r.type,r.capacity,r.amenities,b.checkin,b.checkout,COALESCE(b.actual_out,''),b.status,b.payment_status,b.guests,b.occupants,b.special_requests,b.stay_notes,b.promo_code,b.promo_discount,b.discount,b.cancel_reason,b.cancel_fee FROM bookings b JOIN users u ON u.id=b.guest_id JOIN rooms r ON r.id=b.room_id WHERE b.id=?")){
                q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){if(!r.next())return "Booking not found.";
                    LocalDate in=LocalDate.parse(r.getString(9)),out=LocalDate.parse(r.getString(10));long nights=Math.max(1,ChronoUnit.DAYS.between(in,out));
                    int roomId=0;try(PreparedStatement rq=db.prepareStatement("SELECT room_id FROM bookings WHERE id=?")){rq.setInt(1,bookingId);try(ResultSet rr=rq.executeQuery()){if(rr.next())roomId=rr.getInt(1);}}
                    double roomAmt=roomAmount(roomId,in,out);double svc=servicesTotal(bookingId);double sub=roomAmt+svc;double discPct=billDiscountPct(bookingId);double discAmt=sub*discPct/100;double tax=(sub-discAmt)*settingDouble("tax_rate",0)/100;double total="Checked out".equals(r.getString(12))?billTotal(bookingId):sub-discAmt+tax;double paid=paidNet(bookingId);
                    StringBuilder sb=new StringBuilder();
                    sb.append("Booking code: ").append(r.getString(1)).append("\nGuest: ").append(r.getString(2)).append(" · ").append(r.getString(3)).append(" · ").append(r.getString(4)==null?"":r.getString(4));
                    sb.append("\nRoom: ").append(r.getString(5)).append(" (").append(r.getString(6)).append(", capacity ").append(r.getInt(7)).append(")");
                    sb.append("\nAmenities: ").append(r.getString(8));
                    sb.append("\nStay: ").append(r.getString(9)).append(" — ").append(r.getString(10)).append(" (").append(nights).append(" nights)");
                    if(!r.getString(11).isEmpty())sb.append("\nActual check-out: ").append(r.getString(11));
                    sb.append("\nStatus: ").append(r.getString(12)).append(" · Payment: ").append(r.getString(13));
                    sb.append("\nGuests: ").append(r.getInt(14));
                    if(!r.getString(15).isEmpty())sb.append("\nOccupants: ").append(r.getString(15));
                    if(!r.getString(16).isEmpty())sb.append("\nSpecial requests: ").append(r.getString(16));
                    if(!r.getString(17).isEmpty())sb.append("\nStay notes: ").append(r.getString(17));
                    sb.append("\n\nRoom charge: ").append(money(roomAmt));
                    sb.append("\nConfirmed services: ").append(money(svc));
                    sb.append("\nSubtotal: ").append(money(sub));
                    sb.append("\nDiscount (").append(discPct).append("%");
                    if(!r.getString(18).isEmpty())sb.append(", promo ").append(r.getString(18));
                    sb.append("): -").append(money(discAmt));
                    sb.append("\nTax (").append(settingDouble("tax_rate",0)).append("%): ").append(money(tax));
                    sb.append("\nTOTAL: ").append(money(total));
                    sb.append("\nPaid: ").append(money(paid)).append(" · Balance: ").append(money(total-paid));
                    if(!r.getString(20).isEmpty())sb.append("\nCancel reason: ").append(r.getString(20)).append(" · Cancel fee kept: ").append(money(r.getDouble(21)));
                    sb.append("\n\nHotel: ").append(setting("hotel_name","HotelFlow")).append(" · Check-in ").append(setting("checkin_time","14:00")).append(" · Check-out ").append(setting("checkout_time","12:00"));
                    sb.append("\nCancellation policy: ").append(setting("cancellation_policy",""));
                    return sb.toString();
                }
            }
        }
        void showBookingDetail(int bookingId){try{String text=bookingDetailText(bookingId);JTextArea a=new JTextArea(text,22,55);a.setEditable(false);a.setLineWrap(true);JOptionPane.showMessageDialog(this,new JScrollPane(a),"Booking details",JOptionPane.INFORMATION_MESSAGE);}catch(Exception e){err(this,e);}}
        int createBookingRecord(int guestId,int roomId,LocalDate in,LocalDate out,int guests,String occupants,String specialRequests,String promoCode,double promoDisc)throws Exception{
            if(!isRoomAvailable(roomId,in,out,-1))throw new IllegalArgumentException("The room is already booked for these dates, or it is closed / under maintenance.");
            int id;
            try(PreparedStatement p=db.prepareStatement("INSERT INTO bookings(guest_id,room_id,checkin,checkout,guests,occupants,special_requests,promo_code,promo_discount,booking_code) VALUES(?,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                p.setInt(1,guestId);p.setInt(2,roomId);p.setString(3,in.toString());p.setString(4,out.toString());p.setInt(5,guests);p.setString(6,occupants==null?"":occupants);p.setString(7,specialRequests==null?"":specialRequests);p.setString(8,promoCode==null?"":promoCode);p.setDouble(9,promoDisc);p.setString(10,"TEMP");p.executeUpdate();
                try(ResultSet k=p.getGeneratedKeys()){if(!k.next())throw new Exception("Booking was not created.");id=k.getInt(1);}
            }
            try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET booking_code=?, payment_status='Unpaid' WHERE id=?")){p.setString(1,bookingCodeFor(id));p.setInt(2,id);p.executeUpdate();}
            logAction("Create booking","Booking "+bookingCodeFor(id)+" room "+roomNumberOf(roomId)+" "+in+" to "+out);
            try{notifyGuest(id,"Booking","Booking confirmed "+bookingCodeFor(id),"Your booking for room "+roomNumberOf(roomId)+" from "+in+" to "+out+" is confirmed. You can pay a deposit from My bookings.");}catch(Exception ignored){}
            return id;
        }

        void emailText(String to,String subject,String body){try{
            if(to==null||to.trim().isEmpty()){info(this,"No email address on file. Please add an email in My account (for customers) or in Customers (for staff) first.");return;}
            if(java.awt.Desktop.isDesktopSupported()&&java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MAIL)){
                String uri="mailto:"+java.net.URLEncoder.encode(to.trim(),"UTF-8").replace("+","%20")+"?subject="+java.net.URLEncoder.encode(subject,"UTF-8").replace("+","%20")+"&body="+java.net.URLEncoder.encode(body,"UTF-8").replace("+","%20");
                java.awt.Desktop.getDesktop().mail(new java.net.URI(uri));info(this,"Your default mail app was opened with the email ready to send to "+to+". If it did not open, use the saved text file instead.");
            }else{
                saveTextFile("Email_to_"+to.replaceAll("[^A-Za-z0-9]","_")+".txt","To: "+to+"\nSubject: "+subject+"\n\n"+body);info(this,"No mail app is available on this computer, so the email text was saved as a file instead. You can copy it into your own email.");
            }
        }catch(Exception e){err(this,e);}}
        void emailBooking(int bookingId,boolean useInvoice){try{
            String to="",code="";try(PreparedStatement q=db.prepareStatement("SELECT u.email,b.booking_code FROM bookings b JOIN users u ON u.id=b.guest_id WHERE b.id=?")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){if(r.next()){to=r.getString(1)==null?"":r.getString(1);code=r.getString(2);}}}
            String body=useInvoice?invoice(bookingId):bookingDetailText(bookingId);
            emailText(to,useInvoice?"HotelFlow invoice "+code:"HotelFlow booking confirmation "+code,body);
        }catch(Exception e){err(this,e);}}
        void roomTypesPage(){page("Room types (Admin)","Room types are the master list used by room prices, promotions and the room form. Editing a type here changes the defaults for NEW rooms only — rooms already created keep their own price, capacity and amenities unless you edit the room itself.");
            try{JTable t=new JTable(query("SELECT id 'ID',name 'Type',description 'Description',base_price 'Base price',capacity 'Capacity',amenities 'Amenities',active 'Active' FROM room_types ORDER BY name"));
                JButton add=button("＋ Add type",TEAL),edit=button("Edit selected",NAVY),toggle=button("Activate / deactivate",NAVY),del=button("Delete type",new Color(176,58,58));
                add.addActionListener(e->roomTypeDialog(-1,t));edit.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a type first.");return;}roomTypeDialog(Integer.parseInt(t.getValueAt(row,0).toString()),t);});
                toggle.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a type first.");return;}try{int id=Integer.parseInt(t.getValueAt(row,0).toString());try(PreparedStatement p=db.prepareStatement("UPDATE room_types SET active=CASE WHEN active=1 THEN 0 ELSE 1 END WHERE id=?")){p.setInt(1,id);p.executeUpdate();}t.setModel(query("SELECT id 'ID',name 'Type',description 'Description',base_price 'Base price',capacity 'Capacity',amenities 'Amenities',active 'Active' FROM room_types ORDER BY name"));}catch(Exception ex){err(this,ex);}});
                del.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a type first.");return;}try{int id=Integer.parseInt(t.getValueAt(row,0).toString());String name=t.getValueAt(row,1).toString();int used=(int)scalar("SELECT COUNT(*) FROM rooms WHERE type='"+name.replace("'","''")+"'");if(used>0){info(this,"Cannot delete '"+name+"' — it is used by "+used+" room(s). Deactivate it instead, or change those rooms to another type first.");return;}if(JOptionPane.showConfirmDialog(this,"Delete room type '"+name+"'? Price rules and promotions that use this type name will no longer match new rooms.","Delete type",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try(PreparedStatement p=db.prepareStatement("DELETE FROM room_types WHERE id=?")){p.setInt(1,id);p.executeUpdate();}t.setModel(query("SELECT id 'ID',name 'Type',description 'Description',base_price 'Base price',capacity 'Capacity',amenities 'Amenities',active 'Active' FROM room_types ORDER BY name"));}catch(Exception ex){err(this,ex);}});
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);for(JButton b:new JButton[]{add,edit,toggle,del})top.add(b);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();}
        void roomTypeDialog(int typeId,JTable table){try{
            String name="",desc="",amen="";double price=0;int cap=2;boolean active=true;
            if(typeId>0){try(PreparedStatement q=db.prepareStatement("SELECT name,description,base_price,capacity,amenities,active FROM room_types WHERE id=?")){q.setInt(1,typeId);try(ResultSet r=q.executeQuery()){if(r.next()){name=r.getString(1);desc=r.getString(2)==null?"":r.getString(2);price=r.getDouble(3);cap=r.getInt(4);amen=r.getString(5)==null?"":r.getString(5);active=r.getInt(6)==1;}}}}
            JTextField nameF=new JTextField(name),priceF=new JTextField(String.valueOf(price)),capF=new JTextField(String.valueOf(cap)),amenF=new JTextField(amen);JTextArea descA=new JTextArea(desc,3,40);descA.setLineWrap(true);JCheckBox activeBox=new JCheckBox("Active (appears in room form and price/promotion lists)",active);
            Object[] f={"Type name",nameF,"Description",new JScrollPane(descA),"Base price / night (VND, default for new rooms)",priceF,"Default capacity (guests)",capF,"Default amenities",amenF,activeBox};
            if(JOptionPane.showConfirmDialog(this,f,typeId>0?"Edit room type":"Add room type",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            int newCap=Integer.parseInt(capF.getText().trim());if(newCap<=0)throw new IllegalArgumentException("Capacity must be greater than 0.");
            if(typeId>0){try(PreparedStatement p=db.prepareStatement("UPDATE room_types SET name=?,description=?,base_price=?,capacity=?,amenities=?,active=? WHERE id=?")){p.setString(1,nameF.getText().trim());p.setString(2,descA.getText());p.setDouble(3,Double.parseDouble(priceF.getText()));p.setInt(4,newCap);p.setString(5,amenF.getText());p.setInt(6,activeBox.isSelected()?1:0);p.setInt(7,typeId);p.executeUpdate();}logAction("Update room type","Type "+nameF.getText().trim());}
            else{createRoomType(nameF.getText().trim(),descA.getText(),Double.parseDouble(priceF.getText()),newCap,amenF.getText());logAction("Add room type","Type "+nameF.getText().trim());}
            table.setModel(query("SELECT id 'ID',name 'Type',description 'Description',base_price 'Base price',capacity 'Capacity',amenities 'Amenities',active 'Active' FROM room_types ORDER BY name"));info(this,"Room type saved. Existing rooms keep their own values — edit a room itself to change its price or capacity.");
        }catch(IllegalArgumentException ex){info(this,ex.getMessage());}catch(Exception e){err(this,e);}}
        void notificationsPage(){page("Notifications","Messages about your bookings, payments, check-in / check-out and staff replies. Staff also see shift assignments here.");
            try{JTable t=new JTable();Runnable reload=()->{try{t.setModel(query("SELECT id 'ID',type 'Type',title 'Title',message 'Message',status 'Status',created_at 'When' FROM notifications WHERE user_id="+user.id()+" ORDER BY id DESC"));}catch(Exception ex){err(this,ex);}};reload.run();
                JButton read=button("Mark as read",NAVY),allRead=button("Mark all as read",NAVY),detail=button("View full message",NAVY);
                read.addActionListener(e->{int id=selected(t);if(id<=0)return;try{try(PreparedStatement p=db.prepareStatement("UPDATE notifications SET status='Read' WHERE id=? AND user_id=?")){p.setInt(1,id);p.setInt(2,user.id());p.executeUpdate();}reload.run();}catch(Exception ex){err(this,ex);}});
                allRead.addActionListener(e->{try{try(PreparedStatement p=db.prepareStatement("UPDATE notifications SET status='Read' WHERE user_id=?")){p.setInt(1,user.id());p.executeUpdate();}reload.run();}catch(Exception ex){err(this,ex);}});
                detail.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a notification first.");return;}JTextArea a=new JTextArea(t.getValueAt(row,3).toString(),10,55);a.setEditable(false);a.setLineWrap(true);JOptionPane.showMessageDialog(this,new JScrollPane(a),t.getValueAt(row,2).toString(),JOptionPane.INFORMATION_MESSAGE);});
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);for(JButton b:new JButton[]{read,allRead,detail})top.add(b);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();}
        void editBookingDialog(int bookingId){try{
            int roomId,guestId,guests;LocalDate in,out;String status,occupants,special,stayNotes;
            try(PreparedStatement q=db.prepareStatement("SELECT room_id,guest_id,guests,checkin,checkout,status,occupants,special_requests,stay_notes FROM bookings WHERE id=?")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){if(!r.next()){info(this,"Booking not found.");return;}roomId=r.getInt(1);guestId=r.getInt(2);guests=r.getInt(3);in=LocalDate.parse(r.getString(4));out=LocalDate.parse(r.getString(5));status=r.getString(6);occupants=r.getString(7)==null?"":r.getString(7);special=r.getString(8)==null?"":r.getString(8);stayNotes=r.getString(9)==null?"":r.getString(9);}}
            if(!"Reserved".equals(status)&&!"Occupied".equals(status)){info(this,"Only a Reserved or checked-in booking can be edited (status: "+status+").");return;}
            boolean isAdmin=user.role().equals("ADMIN");
            JComboBox<String> guestBox=new JComboBox<>();Map<String,Integer> guestIds=new HashMap<>();
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,name FROM users WHERE role='CUSTOMER' ORDER BY name")){while(r.next()){String n=r.getString(2)+" (#"+r.getInt(1)+")";guestBox.addItem(n);guestIds.put(n,r.getInt(1));}}
            for(String n:guestIds.keySet())if(guestIds.get(n)==guestId)guestBox.setSelectedItem(n);
            if("Occupied".equals(status)&&!isAdmin)guestBox.setEnabled(false);
            JComboBox<String> roomBox=new JComboBox<>();Map<String,Integer> roomIds=new HashMap<>();
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,number,type,price FROM rooms WHERE is_active=1 AND status NOT IN ('Maintenance','Closed') ORDER BY number")){while(r.next()){int rid=r.getInt(1);if(rid!=roomId&&!isRoomAvailable(rid,in,out,bookingId))continue;String n=r.getString(2)+" · "+r.getString(3)+" · "+money(r.getDouble(4))+"/night";roomBox.addItem(n);roomIds.put(n,rid);}}
            for(String n:roomIds.keySet())if(roomIds.get(n)==roomId)roomBox.setSelectedItem(n);
            JTextField inF=new JTextField(in.toString()),outF=new JTextField(out.toString()),guestsF=new JTextField(String.valueOf(guests)),occF=new JTextField(occupants);JTextArea specA=new JTextArea(special,3,40);specA.setLineWrap(true);JTextArea notesA=new JTextArea(stayNotes,2,40);notesA.setLineWrap(true);
            if("Occupied".equals(status))inF.setEnabled(false);
            Object[] f={"Guest"+("Occupied".equals(status)&&!isAdmin?" — locked (only Admin can change the guest of a checked-in booking)":""),guestBox,"Room (only rooms free for these dates are listed, plus the current room)",roomBox,"Check-in date (YYYY-MM-DD)"+("Occupied".equals(status)?" — locked for checked-in stays":""),inF,"Check-out date (YYYY-MM-DD)",outF,"Number of guests",guestsF,"Occupant names",occF,"Special requests",new JScrollPane(specA),"Stay notes (staff only)",new JScrollPane(notesA)};
            if(JOptionPane.showConfirmDialog(this,f,"Edit booking",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            LocalDate newIn=parseDate(inF.getText()),newOut=parseDate(outF.getText());int newGuests=Integer.parseInt(guestsF.getText().trim());
            int newGuest=guestIds.get(guestBox.getSelectedItem());int newRoom=roomIds.get(roomBox.getSelectedItem());
            updateBookingInfo(bookingId,newGuest,newRoom,newIn,newOut,newGuests,occF.getText(),specA.getText(),notesA.getText(),isAdmin);
            double newRoomAmt=roomAmount(newRoom,newIn,newOut);
            info(this,"Booking updated.\nNew room charge estimate: "+money(newRoomAmt)+" (services, discounts and tax are recalculated at check-out).");bookings();
        }catch(IllegalArgumentException ex){info(this,ex.getMessage());}catch(Exception e){err(this,e);}}
        void payDialog(int bookingId){try{
            String code="";try(PreparedStatement q=db.prepareStatement("SELECT booking_code FROM bookings WHERE id=?")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){if(r.next())code=r.getString(1);}}
            double total=billTotal(bookingId);double paid=paidNet(bookingId);double balance=total-paid;
            if(balance<=0.5){info(this,"This booking is already paid. Total "+money(total)+", paid "+money(paid)+".");return;}
            JComboBox<String> method=new JComboBox<>(new String[]{"Cash","Card","Transfer"});
            JComboBox<String> type=new JComboBox<>(new String[]{"Deposit","Partial","Full"});
            double deposit=Math.round(total*settingDouble("deposit_percent",30)/100);
            JTextField amount=new JTextField(String.valueOf((long)deposit));JTextField note=new JTextField();
            type.addActionListener(e->{String sel=(String)type.getSelectedItem();if("Full".equals(sel))amount.setText(String.valueOf((long)Math.ceil(balance)));else if("Deposit".equals(sel))amount.setText(String.valueOf((long)deposit));});
            Object[] f={"Booking "+code+"\nTotal "+money(total)+" · Paid "+money(paid)+" · Balance due "+money(balance)+"\nPayment method",method,"Payment type",type,"Amount (VND)",amount,"Note (optional)",note};
            if(JOptionPane.showConfirmDialog(this,f,"Record payment",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            double amt=Double.parseDouble(amount.getText().trim());if(amt<=0)throw new IllegalArgumentException("Amount must be greater than 0.");if(amt>balance+0.5&&!"Full".equals(type.getSelectedItem())){/* allow overpay only as Full, otherwise cap */ }
            int payId;try(PreparedStatement p=db.prepareStatement("INSERT INTO payments(booking_id,method,type,amount,note) VALUES(?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){p.setInt(1,bookingId);p.setString(2,(String)method.getSelectedItem());p.setString(3,(String)type.getSelectedItem());p.setDouble(4,amt);p.setString(5,note.getText());p.executeUpdate();try(ResultSet k=p.getGeneratedKeys()){payId=k.next()?k.getInt(1):-1;}}
            updatePaymentStatus(bookingId);logAction("Record payment","Booking "+code+" "+method.getSelectedItem()+" "+type.getSelectedItem()+" "+money(amt));
            try{notifyGuest(bookingId,"Payment","Payment recorded for "+code,method.getSelectedItem()+" · "+type.getSelectedItem()+" · "+money(amt)+". Paid so far: "+money(paidNet(bookingId))+".");}catch(Exception ignored){}
            String receipt="HOTELFLOW · PAYMENT RECEIPT\nBooking: "+code+"\nMethod: "+method.getSelectedItem()+" · Type: "+type.getSelectedItem()+"\nAmount paid now: "+money(amt)+"\nTotal bill: "+money(total)+"\nPaid so far: "+money(paidNet(bookingId))+"\nRemaining balance: "+money(billTotal(bookingId)-paidNet(bookingId))+"\nThank you!";
            JTextArea a=new JTextArea(receipt,12,50);a.setEditable(false);
            Object[] opts={"Save receipt TXT","Close"};int choice=JOptionPane.showOptionDialog(this,new JScrollPane(a),"Payment recorded",JOptionPane.DEFAULT_OPTION,JOptionPane.INFORMATION_MESSAGE,null,opts,opts[1]);
            if(choice==0)saveTextFile("Receipt_"+code+"_"+payId+".txt",receipt);
            if(user.role().equals("CUSTOMER"))myBookings();else paymentsPage();
        }catch(Exception e){err(this,e);}}
        void customerServicesPage(){
            page("Services during your stay","View the service menu with prices. A request is NOT charged yet — Receptionist confirms it after the service is provided, and only then is it added to your bill.");
            try{
                JTable menu=new JTable(query("SELECT id 'ID',name 'Service',category 'Category',description 'Description',price 'Price',service_hours 'Hours' FROM service_catalog WHERE available=1 ORDER BY category,name"));
                JTextField qty=new JTextField("1",6);
                JComboBox<String> stay=new JComboBox<>();Map<String,Integer> stayIds=new HashMap<>();
                try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT b.id,r.number,b.checkin,b.checkout FROM bookings b JOIN rooms r ON r.id=b.room_id WHERE b.guest_id="+user.id()+" AND b.status='Occupied' ORDER BY b.id DESC")){while(r.next()){String n=r.getString(1)+" · Room "+r.getString(2)+" · "+r.getString(3)+" to "+r.getString(4);stay.addItem(n);stayIds.put(n,r.getInt(1));}}
                JButton requestBtn=button("Request selected service",TEAL);
                requestBtn.addActionListener(e->{try{
                    if(stay.getSelectedItem()==null){info(this,"You need a checked-in stay to request a service. You can still view the menu above.");return;}
                    int row=menu.getSelectedRow();if(row<0){info(this,"Please select a service first.");return;}
                    int svcId=Integer.parseInt(menu.getValueAt(row,0).toString());String svcName=menu.getValueAt(row,1).toString();double price=Double.parseDouble(menu.getValueAt(row,4).toString());int q=Integer.parseInt(qty.getText().trim());if(q<=0)throw new IllegalArgumentException("Quantity must be greater than 0.");
                    double amt=price*q;int bookingId=stayIds.get(stay.getSelectedItem());
                    if(JOptionPane.showConfirmDialog(this,"Request: "+svcName+" x "+q+" = "+money(amt)+"\n\nThis is NOT charged yet. Receptionist will confirm after the service is provided, and only then will it be added to your bill. Do you want to request it?","Confirm service request",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                    try(PreparedStatement p=db.prepareStatement("INSERT INTO service_requests(booking_id,guest_id,service_id,service_name,unit_price,quantity,amount,status) VALUES(?,?,?,?,?,?,?,'Pending')")){p.setInt(1,bookingId);p.setInt(2,user.id());p.setInt(3,svcId);p.setString(4,svcName);p.setDouble(5,price);p.setInt(6,q);p.setDouble(7,amt);p.executeUpdate();}
                    logAction("Request service","Service "+svcName+" x "+q+" for booking "+bookingId);info(this,"Service requested. It will appear as Pending below and will NOT be charged until Receptionist confirms it.");customerServicesPage();
                }catch(Exception ex){err(this,ex);}});
                JTable req=new JTable(query("SELECT sr.id 'ID',sr.service_name 'Service',b.booking_code 'Booking',sr.quantity 'Qty',sr.amount 'Total',sr.status 'Status',sr.created_at 'Requested' FROM service_requests sr JOIN bookings b ON b.id=sr.booking_id WHERE sr.guest_id="+user.id()+" ORDER BY sr.id DESC"));
                JButton cancelReq=button("Cancel pending request",new Color(176,58,58));
                cancelReq.addActionListener(e->{int row=req.getSelectedRow();if(row<0){info(this,"Please select a request first.");return;}int id=Integer.parseInt(req.getValueAt(row,0).toString());try(PreparedStatement p=db.prepareStatement("UPDATE service_requests SET status='Cancelled' WHERE id=? AND guest_id=? AND status='Pending'")){p.setInt(1,id);p.setInt(2,user.id());if(p.executeUpdate()>0){info(this,"Request cancelled. Nothing was charged.");customerServicesPage();}else info(this,"Only a Pending request can be cancelled.");}catch(Exception ex){err(this,ex);}});
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);top.add(label("Your checked-in stay"));top.add(stay);top.add(label("Qty"));top.add(qty);top.add(requestBtn);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(tablePanel(menu),BorderLayout.CENTER);
                JPanel bottom=new JPanel(new BorderLayout(0,8));bottom.setOpaque(false);JLabel rl=new JLabel("My service requests (Pending = not charged yet, Confirmed = added to your bill)");rl.setFont(new Font("SansSerif",Font.BOLD,14));bottom.add(rl,BorderLayout.NORTH);bottom.add(tablePanel(req),BorderLayout.CENTER);bottom.add(cancelReq,BorderLayout.SOUTH);
                JPanel all=new JPanel(new BorderLayout(0,16));all.setOpaque(false);all.add(holder,BorderLayout.CENTER);all.add(bottom,BorderLayout.SOUTH);
                content.add(all,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void myAccountPage(){
            page("My account","Update your profile, contact details, notification preference and password. Username cannot be changed.");
            try{
                String name="",phone="",email="";int notify=1;
                try(PreparedStatement q=db.prepareStatement("SELECT name,phone,email,notifications FROM users WHERE id=?")){q.setInt(1,user.id());try(ResultSet r=q.executeQuery()){if(r.next()){name=r.getString(1);phone=r.getString(2)==null?"":r.getString(2);email=r.getString(3)==null?"":r.getString(3);notify=r.getInt(4);}}}
                JTextField nameF=new JTextField(name),phoneF=new JTextField(phone),emailF=new JTextField(email);JCheckBox notifyBox=new JCheckBox("Receive booking and service notifications",notify==1);
                JPasswordField cur=new JPasswordField(),nw=new JPasswordField(),conf=new JPasswordField();
                JPanel form=new JPanel();form.setBackground(Color.WHITE);form.setBorder(new EmptyBorder(20,20,20,20));form.setLayout(new BoxLayout(form,BoxLayout.Y_AXIS));
                form.add(label("Full name"));form.add(nameF);form.add(Box.createVerticalStrut(10));
                form.add(label("Phone"));form.add(phoneF);form.add(Box.createVerticalStrut(10));
                form.add(label("Email (optional)"));form.add(emailF);form.add(Box.createVerticalStrut(10));
                form.add(notifyBox);form.add(Box.createVerticalStrut(16));
                form.add(label("Change password — leave blank to keep your current password"));form.add(Box.createVerticalStrut(6));
                form.add(label("Current password"));form.add(cur);form.add(Box.createVerticalStrut(8));
                form.add(label("New password (at least 6 characters)"));form.add(nw);form.add(Box.createVerticalStrut(8));
                form.add(label("Confirm new password"));form.add(conf);form.add(Box.createVerticalStrut(16));
                JButton save=button("Save profile",TEAL);
                save.addActionListener(e->{try{
                    String nn=nameF.getText().trim();if(nn.isEmpty())throw new IllegalArgumentException("Full name cannot be empty.");
                    String em=emailF.getText().trim();if(!em.isEmpty()&&!em.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))throw new IllegalArgumentException("Please enter a valid email, or leave it blank.");
                    String ph=phoneF.getText().trim();if(!ph.isEmpty()&&ph.replaceAll("[^0-9]","").length()<7)throw new IllegalArgumentException("Please enter a valid phone number (at least 7 digits), or leave it blank.");
                    String newPw=new String(nw.getPassword());
                    if(!newPw.isEmpty()){
                        String stored="";try(PreparedStatement q=db.prepareStatement("SELECT password FROM users WHERE id=?")){q.setInt(1,user.id());try(ResultSet r=q.executeQuery()){if(r.next())stored=r.getString(1);}}
                        if(!checkPassword(new String(cur.getPassword()),stored))throw new IllegalArgumentException("Current password is incorrect.");
                        if(newPw.length()<6)throw new IllegalArgumentException("New password must be at least 6 characters.");
                        if(!newPw.equals(new String(conf.getPassword())))throw new IllegalArgumentException("New password and confirmation do not match.");
                        try(PreparedStatement p=db.prepareStatement("UPDATE users SET name=?,phone=?,email=?,notifications=?,password=? WHERE id=?")){p.setString(1,nn);p.setString(2,ph);p.setString(3,em);p.setInt(4,notifyBox.isSelected()?1:0);p.setString(5,encodePassword(newPw));p.setInt(6,user.id());p.executeUpdate();}
                    }else{
                        try(PreparedStatement p=db.prepareStatement("UPDATE users SET name=?,phone=?,email=?,notifications=? WHERE id=?")){p.setString(1,nn);p.setString(2,ph);p.setString(3,em);p.setInt(4,notifyBox.isSelected()?1:0);p.setInt(5,user.id());p.executeUpdate();}
                    }
                    logAction("Update profile","User "+user.id()+" updated profile");
                    user=new User(user.id(),nn,user.role());info(this,"Profile saved.");dispose();new Dashboard().setVisible(true);
                }catch(Exception ex){err(this,ex);}});
                form.add(save);content.add(form,BorderLayout.NORTH);
            }catch(Exception e){err(this,e);}refresh();
        }
        void supportPage(){
            page("Support","Send a help request about a booking, or contact the hotel. Staff replies will appear below.");
            try{
                String contact="Hotel: "+setting("hotel_name","HotelFlow")+" · Phone: "+setting("hotel_phone","")+" · Email: "+setting("hotel_email","");
                JLabel contactLabel=new JLabel(contact);contactLabel.setForeground(Color.GRAY);
                JComboBox<String> bookingBox=new JComboBox<>();Map<String,Integer> bookingIds=new HashMap<>();bookingBox.addItem("General (no specific booking)");bookingIds.put("General (no specific booking)",-1);
                String guestFilter=user.role().equals("CUSTOMER")?" WHERE b.guest_id="+user.id():"";
                try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT b.id,b.booking_code,r.number FROM bookings b JOIN rooms r ON r.id=b.room_id"+guestFilter+" ORDER BY b.id DESC")){while(r.next()){String n=r.getString(2)+" · Room "+r.getString(3);bookingBox.addItem(n);bookingIds.put(n,r.getInt(1));}}
                JTextField subject=new JTextField(40);JTextArea msg=new JTextArea(4,50);msg.setLineWrap(true);
                JButton send=button("Send support request",TEAL);
                send.addActionListener(e->{try{
                    if(subject.getText().trim().isEmpty()||msg.getText().trim().isEmpty())throw new IllegalArgumentException("Please enter a subject and a message.");
                    int bid=bookingIds.get(bookingBox.getSelectedItem());
                    try(PreparedStatement p=db.prepareStatement("INSERT INTO support_requests(guest_id,booking_id,subject,message,status) VALUES(?,?,?,?, 'Open')")){p.setInt(1,user.id());if(bid<0)p.setNull(2,Types.INTEGER);else p.setInt(2,bid);p.setString(3,subject.getText().trim());p.setString(4,msg.getText().trim());p.executeUpdate();}
                    logAction("Support request","Subject: "+subject.getText().trim());info(this,"Support request sent. Staff will reply here.");supportPage();
                }catch(Exception ex){err(this,ex);}});
                JPanel form=new JPanel(new FlowLayout(FlowLayout.LEFT));form.setOpaque(false);form.add(label("Booking"));form.add(bookingBox);form.add(label("Subject"));form.add(subject);
                JPanel form2=new JPanel(new BorderLayout(0,8));form2.setOpaque(false);form2.add(form,BorderLayout.NORTH);form2.add(new JScrollPane(msg),BorderLayout.CENTER);form2.add(send,BorderLayout.SOUTH);
                String listSql=user.role().equals("CUSTOMER")?"SELECT sr.id 'ID',sr.subject 'Subject',COALESCE(b.booking_code,'-') 'Booking',sr.status 'Status',sr.created_at 'Created',COALESCE(sr.reply,'') 'Staff reply' FROM support_requests sr LEFT JOIN bookings b ON b.id=sr.booking_id WHERE sr.guest_id="+user.id()+" ORDER BY sr.id DESC":"SELECT sr.id 'ID',u.name 'Guest',sr.subject 'Subject',COALESCE(b.booking_code,'-') 'Booking',sr.status 'Status',sr.created_at 'Created' FROM support_requests sr JOIN users u ON u.id=sr.guest_id LEFT JOIN bookings b ON b.id=sr.booking_id ORDER BY sr.id DESC";
                JTable list=new JTable(query(listSql));
                JButton replyBtn=null,resolveBtn=null;
                if(!user.role().equals("CUSTOMER")){
                    replyBtn=button("Reply / update status",NAVY);resolveBtn=button("Mark resolved",TEAL);
                    replyBtn.addActionListener(e->{int row=list.getSelectedRow();if(row<0){info(this,"Please select a request first.");return;}int id=Integer.parseInt(list.getValueAt(row,0).toString());JTextArea rep=new JTextArea(4,45);rep.setLineWrap(true);JComboBox<String> st=new JComboBox<>(new String[]{"Open","In progress","Resolved"});Object[] f={"Staff reply",new JScrollPane(rep),"Status",st};if(JOptionPane.showConfirmDialog(this,f,"Reply to support request",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try{int bid=-1,guest=-1;try(PreparedStatement q=db.prepareStatement("SELECT booking_id,guest_id FROM support_requests WHERE id=?")){q.setInt(1,id);try(ResultSet r=q.executeQuery()){if(r.next()){bid=r.getInt(2)==0?-1:r.getInt(1);guest=r.getInt(2);}}}try(PreparedStatement p=db.prepareStatement("UPDATE support_requests SET reply=?,status=? WHERE id=?")){p.setString(1,rep.getText());p.setString(2,(String)st.getSelectedItem());p.setInt(3,id);p.executeUpdate();}if(guest>0)createNotification(guest,bid>0?bid:null,"Support","Support reply: "+st.getSelectedItem(),rep.getText());}catch(Exception ex){err(this,ex);}supportPage();});
                    resolveBtn.addActionListener(e->{int row=list.getSelectedRow();if(row<0){info(this,"Please select a request first.");return;}int id=Integer.parseInt(list.getValueAt(row,0).toString());try(PreparedStatement p=db.prepareStatement("UPDATE support_requests SET status='Resolved' WHERE id=?")){p.setInt(1,id);p.executeUpdate();supportPage();}catch(Exception ex){err(this,ex);}});
                }
                JPanel top=new JPanel(new BorderLayout(0,8));top.setOpaque(false);top.add(contactLabel,BorderLayout.NORTH);top.add(form2,BorderLayout.CENTER);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(tablePanel(list),BorderLayout.CENTER);
                if(replyBtn!=null){JPanel btns=new JPanel(new FlowLayout(FlowLayout.LEFT));btns.setOpaque(false);btns.add(replyBtn);btns.add(resolveBtn);holder.add(btns,BorderLayout.SOUTH);}
                content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void shiftDashboard(){
            page("Shift dashboard","Today's arrivals, in-house guests, departures, vacant rooms and rooms that need attention.");
            try{
                String today=LocalDate.now().toString();
                JPanel cards=new JPanel(new GridLayout(1,5,10,0));cards.setOpaque(false);
                cards.add(card("ARRIVALS TODAY",String.valueOf(scalar("SELECT COUNT(*) FROM bookings WHERE status='Reserved' AND checkin='"+today+"'")),"Guests arriving today"));
                cards.add(card("IN-HOUSE",String.valueOf(scalar("SELECT COUNT(*) FROM bookings WHERE status='Occupied'")),"Guests staying now"));
                cards.add(card("DEPARTURES TODAY",String.valueOf(scalar("SELECT COUNT(*) FROM bookings WHERE status='Occupied' AND checkout='"+today+"'")),"Guests leaving today"));
                cards.add(card("VACANT ROOMS",String.valueOf(scalar("SELECT COUNT(*) FROM rooms WHERE status='Available' AND is_active=1")),"Ready to sell"));
                cards.add(card("ROOMS TO HANDLE",String.valueOf(scalar("SELECT COUNT(*) FROM rooms WHERE status IN ('Cleaning','Maintenance','Closed')")),"Cleaning / maintenance / closed"));
                JTable arrivals=new JTable(query("SELECT b.booking_code 'Code',u.name 'Guest',u.phone 'Phone',r.number 'Room',b.checkin 'Check-in',b.checkout 'Check-out' FROM bookings b JOIN users u ON u.id=b.guest_id JOIN rooms r ON r.id=b.room_id WHERE b.status='Reserved' AND b.checkin='"+today+"' ORDER BY b.id"));
                JTable departures=new JTable(query("SELECT b.booking_code 'Code',u.name 'Guest',r.number 'Room',b.checkin 'Check-in',b.checkout 'Check-out' FROM bookings b JOIN users u ON u.id=b.guest_id JOIN rooms r ON r.id=b.room_id WHERE b.status='Occupied' AND b.checkout='"+today+"' ORDER BY b.id"));
                JTable toHandle=new JTable(query("SELECT id 'ID',number 'Room',type 'Type',status 'Status' FROM rooms WHERE status IN ('Cleaning','Maintenance','Closed') ORDER BY number"));
                JPanel lists=new JPanel(new GridLayout(3,1,0,12));lists.setOpaque(false);
                JPanel a1=new JPanel(new BorderLayout());a1.setOpaque(false);a1.add(new JLabel("Arrivals today"),BorderLayout.NORTH);a1.add(tablePanel(arrivals),BorderLayout.CENTER);
                JPanel a2=new JPanel(new BorderLayout());a2.setOpaque(false);a2.add(new JLabel("Departures today"),BorderLayout.NORTH);a2.add(tablePanel(departures),BorderLayout.CENTER);
                JPanel a3=new JPanel(new BorderLayout());a3.setOpaque(false);a3.add(new JLabel("Rooms to handle"),BorderLayout.NORTH);a3.add(tablePanel(toHandle),BorderLayout.CENTER);
                lists.add(a1);lists.add(a2);lists.add(a3);
                JPanel holder=new JPanel(new BorderLayout(0,14));holder.setOpaque(false);holder.add(cards,BorderLayout.NORTH);holder.add(lists,BorderLayout.CENTER);
                content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void inHouseGuests(){
            page("Guests staying now","Checked-in guests. Extend a stay, change rooms, add a note, or check out early from here.");
            try{
                JTable t=new JTable(query("SELECT b.id 'ID',b.booking_code 'Code',u.name 'Guest',u.phone 'Phone',r.number 'Room',b.checkin 'Check-in',b.checkout 'Check-out',b.guests 'Guests',COALESCE(b.special_requests,'') 'Requests' FROM bookings b JOIN users u ON u.id=b.guest_id JOIN rooms r ON r.id=b.room_id WHERE b.status='Occupied' ORDER BY b.checkout"));
                JButton extendBtn=button("Extend stay",NAVY),changeRoomBtn=button("Change room",NAVY),noteBtn=button("Add / edit note",NAVY),earlyBtn=button("Early check-out",TEAL);
                extendBtn.addActionListener(e->{extendBooking(t);inHouseGuests();});
                changeRoomBtn.addActionListener(e->changeRoomDialog(t));
                noteBtn.addActionListener(e->{int id=selected(t);if(id<0)return;try{String cur="";try(PreparedStatement q=db.prepareStatement("SELECT COALESCE(stay_notes,'') FROM bookings WHERE id=?")){q.setInt(1,id);try(ResultSet r=q.executeQuery()){if(r.next())cur=r.getString(1);}}JTextArea a=new JTextArea(cur,4,45);a.setLineWrap(true);if(JOptionPane.showConfirmDialog(this,new JScrollPane(a),"Stay note",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET stay_notes=? WHERE id=?")){p.setString(1,a.getText());p.setInt(2,id);p.executeUpdate();}inHouseGuests();}catch(Exception ex){err(this,ex);}});
                earlyBtn.addActionListener(e->{int id=selected(t);if(id<0)return;try{try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET stay_notes=COALESCE(stay_notes,'')||' [Early check-out requested]' WHERE id=?")){p.setInt(1,id);p.executeUpdate();}checkout(t);}catch(Exception ex){err(this,ex);}});
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);for(JButton b:new JButton[]{extendBtn,changeRoomBtn,noteBtn,earlyBtn})top.add(b);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void changeRoomDialog(JTable t){int id=selected(t);if(id<0)return;try{
            int oldRoom;LocalDate in,out;String status;
            try(PreparedStatement q=db.prepareStatement("SELECT room_id,checkin,checkout,status FROM bookings WHERE id=?")){q.setInt(1,id);try(ResultSet r=q.executeQuery()){if(!r.next()){info(this,"Booking not found.");return;}oldRoom=r.getInt(1);in=LocalDate.parse(r.getString(2));out=LocalDate.parse(r.getString(3));status=r.getString(4);}}
            if(!"Reserved".equals(status)&&!"Occupied".equals(status)){info(this,"Only a Reserved or checked-in booking can change rooms.");return;}
            JComboBox<String> rooms=new JComboBox<>();Map<String,Integer> roomIds=new HashMap<>();
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,number,type,price FROM rooms WHERE is_active=1 AND status NOT IN ('Maintenance','Closed') ORDER BY number")){while(r.next()){int rid=r.getInt(1);if(rid==oldRoom)continue;if(!isRoomAvailable(rid,in,out,id))continue;String n=r.getString(2)+" · "+r.getString(3)+" · "+money(r.getDouble(4))+"/night";rooms.addItem(n);roomIds.put(n,rid);}}
            if(rooms.getItemCount()==0){info(this,"No other room is available for these dates.");return;}
            JTextField reason=new JTextField();
            Object[] f={"New room (available for these dates)",rooms,"Reason (e.g. room shortage, guest request, maintenance)",reason};
            if(JOptionPane.showConfirmDialog(this,f,"Change room",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            int newRoom=roomIds.get(rooms.getSelectedItem());double oldAmt=roomAmount(oldRoom,in,out);double newAmt=roomAmount(newRoom,in,out);
            if(JOptionPane.showConfirmDialog(this,"Room charge changes from "+money(oldAmt)+" to "+money(newAmt)+" (difference "+money(newAmt-oldAmt)+").\nThe old room will be marked Cleaning if the guest was checked in, otherwise it stays Available.\nConfirm room change?","Confirm room change",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET room_id=?, stay_notes=COALESCE(stay_notes,'')||? WHERE id=?")){p.setInt(1,newRoom);p.setString(2," [Room changed from "+roomNumberOf(oldRoom)+" to "+roomNumberOf(newRoom)+" on "+LocalDate.now()+": "+reason.getText()+"]");p.setInt(3,id);p.executeUpdate();}
            try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET status=? WHERE id=?")){p.setString(1,"Occupied".equals(status)?"Cleaning":"Available");p.setInt(2,oldRoom);p.executeUpdate();}
            try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET status='Occupied' WHERE id=? AND ?='Occupied'")){p.setInt(1,newRoom);p.setString(2,status);p.executeUpdate();}
            // For a Reserved booking the new room stays Available until check-in; for Occupied it is now Occupied (handled above by the conditional update).
            if("Reserved".equals(status)){try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET status='Available' WHERE id=?")){p.setInt(1,newRoom);p.executeUpdate();}}
            logAction("Change room","Booking "+id+" from room "+oldRoom+" to "+newRoom+" reason: "+reason.getText());
            info(this,"Room changed.");if(user.role().equals("CUSTOMER"))myBookings();else bookings();
        }catch(Exception e){err(this,e);}}
        void declineServiceRequest(int reqId){try{
            int bid=-1;
            try(PreparedStatement q=db.prepareStatement("SELECT booking_id FROM service_requests WHERE id=?")){q.setInt(1,reqId);try(ResultSet r=q.executeQuery()){if(r.next())bid=r.getInt(1);}}
            boolean done=false;
            try(PreparedStatement upd=db.prepareStatement("UPDATE service_requests SET status='Cancelled' WHERE id=? AND status='Pending'")){upd.setInt(1,reqId);done=upd.executeUpdate()>0;}
            if(done){
                if(bid>0){try{notifyGuest(bid,"Service","Service request declined","Your service request was declined by staff. Nothing was charged to your bill.");}catch(Exception ignored){}}
                info(this,"Request declined. Nothing was charged.");staffServicesPage();
            }
        }catch(Exception ex){err(this,ex);}}
        void staffServicesPage(){

            page("Services & requests","Customer requests are Pending and NOT charged. Confirm a request only after the service was provided — then it is added to the bill. Manual services below are for services already provided.");
            try{
                JTable pending=new JTable(query("SELECT sr.id 'ID',sr.service_name 'Service',b.booking_code 'Booking',u.name 'Guest',sr.quantity 'Qty',sr.amount 'Total',sr.created_at 'Requested' FROM service_requests sr JOIN bookings b ON b.id=sr.booking_id JOIN users u ON u.id=sr.guest_id WHERE sr.status='Pending' ORDER BY sr.id"));
                JButton confirmBtn=button("Confirm request → add to bill",TEAL),declineBtn=button("Decline request (no charge)",new Color(176,58,58));
                confirmBtn.addActionListener(e->{int row=pending.getSelectedRow();if(row<0){info(this,"Please select a pending request first.");return;}int reqId=Integer.parseInt(pending.getValueAt(row,0).toString());try{
                    int bookingId,svcId;String svcName;double unit;int qty;double amt;
                    try(PreparedStatement q=db.prepareStatement("SELECT booking_id,service_id,service_name,unit_price,quantity,amount FROM service_requests WHERE id=?")){q.setInt(1,reqId);try(ResultSet r=q.executeQuery()){if(!r.next()){info(this,"Request not found.");return;}bookingId=r.getInt(1);svcId=r.getInt(2);svcName=r.getString(3);unit=r.getDouble(4);qty=r.getInt(5);amt=r.getDouble(6);}}
                    String bStatus="";try(PreparedStatement q=db.prepareStatement("SELECT status FROM bookings WHERE id=?")){q.setInt(1,bookingId);try(ResultSet r=q.executeQuery()){if(r.next())bStatus=r.getString(1);}}
                    if(!"Occupied".equals(bStatus)&&!"Reserved".equals(bStatus)){info(this,"This booking is no longer active, so the request cannot be confirmed.");return;}
                    if(JOptionPane.showConfirmDialog(this,"Confirm: "+svcName+" x "+qty+" = "+money(amt)+"\nThis WILL be added to the guest's bill. Only confirm if the service was actually provided.","Confirm service",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                    try(PreparedStatement p=db.prepareStatement("INSERT INTO booking_services(booking_id,service_id,service_name,unit_price,quantity,amount,status) VALUES(?,?,?,?,?,?, 'Confirmed')")){p.setInt(1,bookingId);p.setInt(2,svcId);p.setString(3,svcName);p.setDouble(4,unit);p.setInt(5,qty);p.setDouble(6,amt);p.executeUpdate();}
                    try(PreparedStatement p=db.prepareStatement("UPDATE service_requests SET status='Confirmed' WHERE id=?")){p.setInt(1,reqId);p.executeUpdate();}
                    updatePaymentStatus(bookingId);logAction("Confirm service","Request "+reqId+" confirmed, "+money(amt)+" added to booking "+bookingId);try{notifyGuest(bookingId,"Service","Service confirmed: "+svcName,svcName+" x "+qty+" = "+money(amt)+" has been added to your bill.");}catch(Exception ignored){}info(this,"Confirmed and added to the bill.");staffServicesPage();
                }catch(Exception ex){err(this,ex);}});
                declineBtn.addActionListener(e->{int row=pending.getSelectedRow();if(row<0){info(this,"Please select a pending request first.");return;}declineServiceRequest(Integer.parseInt(pending.getValueAt(row,0).toString()));});
                JComboBox<String> bookingBox=new JComboBox<>();Map<String,Integer> bookingIds=new HashMap<>();
                try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT b.id,b.booking_code,u.name FROM bookings b JOIN users u ON u.id=b.guest_id WHERE b.status IN ('Reserved','Occupied') ORDER BY b.id DESC")){while(r.next()){String n=r.getString(2)+" · "+r.getString(3);bookingBox.addItem(n);bookingIds.put(n,r.getInt(1));}}
                JTable confirmed=new JTable();
                Runnable loadConfirmed=()->{try{if(bookingBox.getSelectedItem()==null)return;int bid=bookingIds.get(bookingBox.getSelectedItem());confirmed.setModel(query("SELECT id 'ID',service_name 'Service',unit_price 'Unit price',quantity 'Qty',amount 'Total',created_at 'Added' FROM booking_services WHERE booking_id="+bid+" AND status='Confirmed' ORDER BY id DESC"));}catch(Exception ex){err(this,ex);}};
                bookingBox.addActionListener(e->loadConfirmed.run());loadConfirmed.run();
                JButton addManual=button("＋ Add service (already provided)",TEAL);
                addManual.addActionListener(e->{try{
                    if(bookingBox.getSelectedItem()==null){info(this,"Please select a booking first.");return;}
                    JComboBox<String> svcBox=new JComboBox<>();Map<String,Object[]> svcMap=new HashMap<>();
                    try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,name,price FROM service_catalog WHERE available=1 ORDER BY name")){while(r.next()){String n=r.getString(2)+" · "+money(r.getDouble(3));svcBox.addItem(n);svcMap.put(n,new Object[]{r.getInt(1),r.getString(2),r.getDouble(3)});}}
                    svcBox.addItem("Custom service (enter name and price below)");
                    JTextField customName=new JTextField(),customPrice=new JTextField("0"),qty=new JTextField("1");
                    Object[] f={"Service from catalog",svcBox,"Custom service name (only if Custom is selected)",customName,"Custom price (VND, only if Custom)",customPrice,"Quantity",qty};
                    if(JOptionPane.showConfirmDialog(this,f,"Add service to bill",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                    int q=Integer.parseInt(qty.getText().trim());if(q<=0)throw new IllegalArgumentException("Quantity must be greater than 0.");
                    String svcName;double unit;Integer svcId=null;
                    if("Custom service (enter name and price below)".equals(svcBox.getSelectedItem())){svcName=customName.getText().trim();unit=Double.parseDouble(customPrice.getText());if(svcName.isEmpty())throw new IllegalArgumentException("Please enter a custom service name.");}
                    else{Object[] v=svcMap.get(svcBox.getSelectedItem());svcId=(Integer)v[0];svcName=(String)v[1];unit=(Double)v[2];}
                    double amt=unit*q;int bid=bookingIds.get(bookingBox.getSelectedItem());
                    if(JOptionPane.showConfirmDialog(this,"Add to bill: "+svcName+" x "+q+" = "+money(amt)+"\nConfirm only if the service was already provided.","Confirm charge",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                    try(PreparedStatement p=db.prepareStatement("INSERT INTO booking_services(booking_id,service_id,service_name,unit_price,quantity,amount,status) VALUES(?,?,?,?,?,?, 'Confirmed')")){p.setInt(1,bid);if(svcId==null)p.setNull(2,Types.INTEGER);else p.setInt(2,svcId);p.setString(3,svcName);p.setDouble(4,unit);p.setInt(5,q);p.setDouble(6,amt);p.executeUpdate();}
                    updatePaymentStatus(bid);logAction("Add service","Manual service "+svcName+" "+money(amt)+" to booking "+bid);loadConfirmed.run();info(this,"Service added to the bill.");
                }catch(Exception ex){err(this,ex);}});
                JPanel topBtns=new JPanel(new FlowLayout(FlowLayout.LEFT));topBtns.setOpaque(false);topBtns.add(confirmBtn);topBtns.add(declineBtn);
                JPanel pendingPanel=new JPanel(new BorderLayout(0,8));pendingPanel.setOpaque(false);pendingPanel.add(new JLabel("Pending customer requests — confirming adds the charge, declining charges nothing"),BorderLayout.NORTH);pendingPanel.add(tablePanel(pending),BorderLayout.CENTER);pendingPanel.add(topBtns,BorderLayout.SOUTH);
                JPanel manualTop=new JPanel(new FlowLayout(FlowLayout.LEFT));manualTop.setOpaque(false);manualTop.add(label("Booking"));manualTop.add(bookingBox);manualTop.add(addManual);
                JPanel manualPanel=new JPanel(new BorderLayout(0,8));manualPanel.setOpaque(false);manualPanel.add(manualTop,BorderLayout.NORTH);manualPanel.add(tablePanel(confirmed),BorderLayout.CENTER);
                JPanel all=new JPanel(new GridLayout(2,1,0,16));all.setOpaque(false);all.add(pendingPanel);all.add(manualPanel);
                content.add(all,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void paymentsPage(){
            page("Payments","Record cash / card / transfer payments, deposits and partial payments. Refunds and voiding are tracked per booking.");
            try{
                JTable t=new JTable(query("SELECT p.id 'ID',b.booking_code 'Booking',u.name 'Guest',p.method 'Method',p.type 'Type',p.amount 'Amount',p.status 'Status',p.created_at 'Date',COALESCE(p.note,'') 'Note' FROM payments p JOIN bookings b ON b.id=p.booking_id JOIN users u ON u.id=b.guest_id ORDER BY p.id DESC LIMIT 200"));
                JButton recordBtn=button("＋ Record payment",TEAL),refundBtn=button("Refund",NAVY),voidBtn=button("Void payment (Admin only)",new Color(176,58,58)),receiptBtn=button("View receipt",NAVY);
                recordBtn.addActionListener(e->{int bid=askBookingId("Record payment for which booking?");if(bid>0)payDialog(bid);});
                refundBtn.addActionListener(e->{try{
                    int bid=askBookingId("Refund for which booking?");if(bid<=0)return;
                    double paid=paidNet(bid);if(paid<=0){info(this,"This booking has no completed payments to refund.");return;}
                    JTextField amt=new JTextField(String.valueOf((long)paid));JComboBox<String> method=new JComboBox<>(new String[]{"Cash","Card","Transfer"});JTextField reason=new JTextField();
                    Object[] f={"Paid so far: "+money(paid)+"\nRefund amount (VND)",amt,"Refund method",method,"Reason",reason};
                    if(JOptionPane.showConfirmDialog(this,f,"Refund",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                    double a=Double.parseDouble(amt.getText().trim());if(a<=0||a>paid+0.5)throw new IllegalArgumentException("Refund must be between 0 and the amount paid.");
                    try(PreparedStatement p=db.prepareStatement("INSERT INTO payments(booking_id,method,type,amount,note,status) VALUES(?,?, 'Refund',?,?, 'Completed')")){p.setInt(1,bid);p.setString(2,(String)method.getSelectedItem());p.setDouble(3,a);p.setString(4,reason.getText());p.executeUpdate();}
                    updatePaymentStatus(bid);logAction("Refund","Booking "+bid+" refund "+money(a)+" reason: "+reason.getText());info(this,"Refund recorded.");paymentsPage();
                }catch(Exception ex){err(this,ex);}});
                voidBtn.addActionListener(e->{if(!user.role().equals("ADMIN")){info(this,"Only an Admin can void a payment.");return;}int row=t.getSelectedRow();if(row<0){info(this,"Please select a payment first.");return;}int pid=Integer.parseInt(t.getValueAt(row,0).toString());if(JOptionPane.showConfirmDialog(this,"Void this payment? It will no longer count toward the booking's paid total.","Void payment",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try{int bid=0;try(PreparedStatement q=db.prepareStatement("SELECT booking_id FROM payments WHERE id=?")){q.setInt(1,pid);try(ResultSet r=q.executeQuery()){if(r.next())bid=r.getInt(1);}}try(PreparedStatement p=db.prepareStatement("UPDATE payments SET status='Void' WHERE id=?")){p.setInt(1,pid);p.executeUpdate();}if(bid>0)updatePaymentStatus(bid);logAction("Void payment","Payment "+pid+" voided");paymentsPage();}catch(Exception ex){err(this,ex);}});
                receiptBtn.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a payment first.");return;}try{int pid=Integer.parseInt(t.getValueAt(row,0).toString());String txt="";try(PreparedStatement q=db.prepareStatement("SELECT p.method,p.type,p.amount,p.status,p.created_at,b.booking_code,u.name FROM payments p JOIN bookings b ON b.id=p.booking_id JOIN users u ON u.id=b.guest_id WHERE p.id=?")){q.setInt(1,pid);try(ResultSet r=q.executeQuery()){if(r.next())txt="HOTELFLOW · PAYMENT RECEIPT\nBooking: "+r.getString(6)+"\nGuest: "+r.getString(7)+"\nMethod: "+r.getString(1)+" · Type: "+r.getString(2)+"\nAmount: "+money(r.getDouble(3))+"\nStatus: "+r.getString(4)+"\nDate: "+r.getString(5);}}JTextArea a=new JTextArea(txt,10,50);a.setEditable(false);if(JOptionPane.showConfirmDialog(this,new JScrollPane(a),"Receipt",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)saveTextFile("Receipt_payment_"+pid+".txt",txt);}catch(Exception ex){err(this,ex);}});
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);for(JButton b:new JButton[]{recordBtn,refundBtn,voidBtn,receiptBtn})top.add(b);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        int askBookingId(String title){try{
            JComboBox<String> box=new JComboBox<>();Map<String,Integer> ids=new HashMap<>();
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT b.id,b.booking_code,u.name FROM bookings b JOIN users u ON u.id=b.guest_id ORDER BY b.id DESC LIMIT 100")){while(r.next()){String n=r.getString(2)+" · "+r.getString(3);box.addItem(n);ids.put(n,r.getInt(1));}}
            if(box.getItemCount()==0){info(this,"No bookings yet.");return -1;}
            if(JOptionPane.showConfirmDialog(this,box,title,JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return -1;
            return ids.get(box.getSelectedItem());
        }catch(Exception e){err(this,e);return -1;}}
        void handoverPage(){
            page("Shift handover","What the next shift needs to know: today's activity, pending requests, and handover notes.");
            try{
                String today=LocalDate.now().toString();
                StringBuilder sum=new StringBuilder();
                sum.append("Today (").append(today).append(")\n");
                sum.append("Arrivals today: ").append(scalar("SELECT COUNT(*) FROM bookings WHERE status='Reserved' AND checkin='"+today+"'")).append("\n");
                sum.append("In-house now: ").append(scalar("SELECT COUNT(*) FROM bookings WHERE status='Occupied'")).append("\n");
                sum.append("Departures today: ").append(scalar("SELECT COUNT(*) FROM bookings WHERE status='Occupied' AND checkout='"+today+"'")).append("\n");
                sum.append("Pending service requests: ").append(scalar("SELECT COUNT(*) FROM service_requests WHERE status='Pending'")).append("\n");
                sum.append("Open support requests: ").append(scalar("SELECT COUNT(*) FROM support_requests WHERE status='Open'")).append("\n");
                sum.append("Rooms to handle (Cleaning/Maintenance/Closed): ").append(scalar("SELECT COUNT(*) FROM rooms WHERE status IN ('Cleaning','Maintenance','Closed')")).append("\n");
                sum.append("Payments recorded today: ").append(money(scalarD("SELECT COALESCE(SUM(CASE WHEN type='Refund' THEN -amount ELSE amount END),0) FROM payments WHERE status='Completed' AND substr(created_at,1,10)='"+today+"'")));
                JTextArea summary=new JTextArea(sum.toString(),8,60);summary.setEditable(false);
                JTable pendingSvc=new JTable(query("SELECT sr.id 'ID',sr.service_name 'Service',b.booking_code 'Booking',sr.amount 'Total',sr.status 'Status' FROM service_requests sr JOIN bookings b ON b.id=sr.booking_id WHERE sr.status='Pending' ORDER BY sr.id"));
                JTable openSupport=new JTable(query("SELECT sr.id 'ID',u.name 'Guest',sr.subject 'Subject',sr.status 'Status' FROM support_requests sr JOIN users u ON u.id=sr.guest_id WHERE sr.status IN ('Open','In progress') ORDER BY sr.id"));
                JTable notes=new JTable(query("SELECT id 'ID',username 'Author',note 'Note',created_at 'Created' FROM shift_notes ORDER BY id DESC LIMIT 20"));
                JTextArea newNote=new JTextArea(3,60);newNote.setLineWrap(true);
                JButton addNote=button("Add handover note",TEAL);
                addNote.addActionListener(e->{try{if(newNote.getText().trim().isEmpty()){info(this,"Please enter a note first.");return;}try(PreparedStatement p=db.prepareStatement("INSERT INTO shift_notes(user_id,username,note,created_at) VALUES(?,?,?,?)")){p.setInt(1,user.id());p.setString(2,user.name());p.setString(3,newNote.getText().trim());p.setString(4,LocalDateTime.now().toString());p.executeUpdate();}handoverPage();}catch(Exception ex){err(this,ex);}});
                JPanel top=new JPanel(new BorderLayout(0,8));top.setOpaque(false);top.add(new JScrollPane(summary),BorderLayout.CENTER);
                JPanel lists=new JPanel(new GridLayout(3,1,0,12));lists.setOpaque(false);
                JPanel p1=new JPanel(new BorderLayout());p1.setOpaque(false);p1.add(new JLabel("Pending service requests"),BorderLayout.NORTH);p1.add(tablePanel(pendingSvc),BorderLayout.CENTER);
                JPanel p2=new JPanel(new BorderLayout());p2.setOpaque(false);p2.add(new JLabel("Open support requests"),BorderLayout.NORTH);p2.add(tablePanel(openSupport),BorderLayout.CENTER);
                JPanel p3=new JPanel(new BorderLayout());p3.setOpaque(false);p3.add(new JLabel("Handover notes"),BorderLayout.NORTH);p3.add(tablePanel(notes),BorderLayout.CENTER);
                lists.add(p1);lists.add(p2);lists.add(p3);
                JPanel bottom=new JPanel(new BorderLayout(0,8));bottom.setOpaque(false);bottom.add(new JScrollPane(newNote),BorderLayout.CENTER);bottom.add(addNote,BorderLayout.SOUTH);
                JPanel holder=new JPanel(new BorderLayout(0,14));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(lists,BorderLayout.CENTER);holder.add(bottom,BorderLayout.SOUTH);
                content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void customersPage(){
            page("Customers","Guest profiles, booking history and care notes. Staff only — customers never see other guests here.");
            try{
                JTable t=new JTable(query("SELECT u.id 'ID',u.name 'Name',u.username 'Username',COALESCE(u.phone,'') 'Phone',COALESCE(u.email,'') 'Email', (SELECT COUNT(*) FROM bookings b WHERE b.guest_id=u.id) 'Bookings', COALESCE((SELECT SUM(total) FROM bookings b WHERE b.guest_id=u.id AND b.status='Checked out'),0) 'Total spent' FROM users u WHERE u.role='CUSTOMER' ORDER BY u.name"));
                JButton detailBtn=button("View profile & history",TEAL),noteBtn=button("Edit care note",NAVY);
                detailBtn.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a customer first.");return;}int uid=Integer.parseInt(t.getValueAt(row,0).toString());try{
                    String prof="";try(PreparedStatement q=db.prepareStatement("SELECT name,username,phone,email,care_notes FROM users WHERE id=?")){q.setInt(1,uid);try(ResultSet r=q.executeQuery()){if(r.next())prof="Name: "+r.getString(1)+"\nUsername: "+r.getString(2)+"\nPhone: "+r.getString(3)+"\nEmail: "+r.getString(4)+"\nCare notes: "+r.getString(5);}}
                    StringBuilder hist=new StringBuilder(prof+"\n\nBooking history:\n");
                    try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT b.booking_code,r.number,b.checkin,b.checkout,b.status,b.total FROM bookings b JOIN rooms r ON r.id=b.room_id WHERE b.guest_id="+uid+" ORDER BY b.id DESC")){while(r.next()){hist.append(r.getString(1)).append(" · Room ").append(r.getString(2)).append(" · ").append(r.getString(3)).append(" to ").append(r.getString(4)).append(" · ").append(r.getString(5)).append(" · ").append(money(r.getDouble(6))).append("\n");}}
                    JTextArea a=new JTextArea(hist.toString(),20,60);a.setEditable(false);JOptionPane.showMessageDialog(this,new JScrollPane(a),"Customer profile",JOptionPane.INFORMATION_MESSAGE);
                }catch(Exception ex){err(this,ex);}});
                noteBtn.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a customer first.");return;}int uid=Integer.parseInt(t.getValueAt(row,0).toString());try{String cur="";try(PreparedStatement q=db.prepareStatement("SELECT COALESCE(care_notes,'') FROM users WHERE id=?")){q.setInt(1,uid);try(ResultSet r=q.executeQuery()){if(r.next())cur=r.getString(1);}}JTextArea a=new JTextArea(cur,4,45);a.setLineWrap(true);if(JOptionPane.showConfirmDialog(this,new JScrollPane(a),"Care note (e.g. prefers quiet room, allergies, VIP guest)",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try(PreparedStatement p=db.prepareStatement("UPDATE users SET care_notes=? WHERE id=?")){p.setString(1,a.getText());p.setInt(2,uid);p.executeUpdate();}customersPage();}catch(Exception ex){err(this,ex);}});
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);top.add(detailBtn);top.add(noteBtn);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void priceInventoryPage(){
            page("Prices & inventory (Admin)","Seasonal and weekend prices, promotions, and opening / closing rooms for sale. A price rule applies per night; without a rule the room's base price applies.");
            try{
                JTable rules=new JTable(query("SELECT id 'ID',scope_type 'Scope',scope_value 'Scope value',start_date 'From',end_date 'To',price 'Price / night',weekend_only 'Weekend only',note 'Note' FROM price_rules ORDER BY id DESC"));
                JButton addRule=button("＋ Add price rule",TEAL),delRule=button("Delete rule",new Color(176,58,58));
                addRule.addActionListener(e->{try{
                    JComboBox<String> scope=new JComboBox<>(new String[]{"ALL","TYPE","ROOM"});JTextField value=new JTextField();JTextField from=new JTextField(LocalDate.now().toString()),to=new JTextField(LocalDate.now().plusDays(30).toString()),price=new JTextField("800000"),note=new JTextField();JCheckBox weekend=new JCheckBox("Weekend only (Saturday / Sunday)");
                    Object[] f={"Scope (ALL = every room, TYPE = one room type, ROOM = one room ID)",scope,"Scope value (room type like Deluxe, or room ID; leave blank for ALL)",value,"From (YYYY-MM-DD)",from,"To (YYYY-MM-DD)",to,"Price / night (VND)",price,weekend,"Note",note};
                    if(JOptionPane.showConfirmDialog(this,f,"Add price rule",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                    LocalDate a=parseDate(from.getText()),b=parseDate(to.getText());if(a==null||b==null||b.isBefore(a))throw new IllegalArgumentException("Please enter valid From / To dates.");
                    try(PreparedStatement p=db.prepareStatement("INSERT INTO price_rules(scope_type,scope_value,start_date,end_date,price,weekend_only,note) VALUES(?,?,?,?,?,?,?)")){p.setString(1,(String)scope.getSelectedItem());p.setString(2,value.getText().trim());p.setString(3,a.toString());p.setString(4,b.toString());p.setDouble(5,Double.parseDouble(price.getText()));p.setInt(6,weekend.isSelected()?1:0);p.setString(7,note.getText());p.executeUpdate();}
                    logAction("Add price rule",scope.getSelectedItem()+" "+value.getText()+" "+a+" to "+b+" "+price.getText());priceInventoryPage();
                }catch(Exception ex){err(this,ex);}});
                delRule.addActionListener(e->{int row=rules.getSelectedRow();if(row<0){info(this,"Please select a rule first.");return;}int id=Integer.parseInt(rules.getValueAt(row,0).toString());try(PreparedStatement p=db.prepareStatement("DELETE FROM price_rules WHERE id=?")){p.setInt(1,id);p.executeUpdate();priceInventoryPage();}catch(Exception ex){err(this,ex);}});
                JTable promos=new JTable(query("SELECT id 'ID',code 'Code',discount_percent 'Discount %',start_date 'From',end_date 'To',room_type 'Room type',active 'Active' FROM promotions ORDER BY id DESC"));
                JButton addPromo=button("＋ Add promotion",TEAL),togglePromo=button("Activate / deactivate",NAVY);
                addPromo.addActionListener(e->{try{
                    JTextField code=new JTextField(),disc=new JTextField("10"),from=new JTextField(LocalDate.now().toString()),to=new JTextField(LocalDate.now().plusDays(30).toString()),type=new JTextField("ALL");
                    Object[] f={"Promotion code (guests type this code when booking)",code,"Discount %",disc,"From (YYYY-MM-DD)",from,"To (YYYY-MM-DD)",to,"Room type (ALL or e.g. Deluxe)",type};
                    if(JOptionPane.showConfirmDialog(this,f,"Add promotion",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                    double d=Double.parseDouble(disc.getText());if(d<0||d>100)throw new IllegalArgumentException("Discount must be between 0 and 100.");
                    try(PreparedStatement p=db.prepareStatement("INSERT INTO promotions(code,discount_percent,start_date,end_date,room_type,active) VALUES(?,?,?,?,?,1)")){p.setString(1,code.getText().trim());p.setDouble(2,d);p.setString(3,from.getText().trim());p.setString(4,to.getText().trim());p.setString(5,type.getText().trim());p.executeUpdate();}
                    logAction("Add promotion","Code "+code.getText()+" "+d+"%");priceInventoryPage();
                }catch(Exception ex){err(this,ex);}});
                togglePromo.addActionListener(e->{int row=promos.getSelectedRow();if(row<0){info(this,"Please select a promotion first.");return;}int id=Integer.parseInt(promos.getValueAt(row,0).toString());try(PreparedStatement p=db.prepareStatement("UPDATE promotions SET active=CASE WHEN active=1 THEN 0 ELSE 1 END WHERE id=?")){p.setInt(1,id);p.executeUpdate();priceInventoryPage();}catch(Exception ex){err(this,ex);}});
                JComboBox<String> typeBox=new JComboBox<>();typeBox.addItem("ALL");
                try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT DISTINCT type FROM rooms ORDER BY type")){while(r.next())typeBox.addItem(r.getString(1));}
                JButton closeType=button("Close sale for type",new Color(176,58,58)),openType=button("Open sale for type",TEAL);
                closeType.addActionListener(e->{try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET is_active=0 WHERE type=?")){p.setString(1,(String)typeBox.getSelectedItem());if("ALL".equals(typeBox.getSelectedItem())){try(Statement s=db.createStatement()){s.executeUpdate("UPDATE rooms SET is_active=0");}}p.executeUpdate();logAction("Close sale","Type "+typeBox.getSelectedItem());info(this,"Sale closed for the selected type. Those rooms will not appear in customer search.");}catch(Exception ex){err(this,ex);}});
                openType.addActionListener(e->{try{if("ALL".equals(typeBox.getSelectedItem())){try(Statement s=db.createStatement()){s.executeUpdate("UPDATE rooms SET is_active=1");}}else{try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET is_active=1 WHERE type=?")){p.setString(1,(String)typeBox.getSelectedItem());p.executeUpdate();}}logAction("Open sale","Type "+typeBox.getSelectedItem());info(this,"Sale opened.");}catch(Exception ex){err(this,ex);}});
                JPanel rTop=new JPanel(new FlowLayout(FlowLayout.LEFT));rTop.setOpaque(false);rTop.add(addRule);rTop.add(delRule);
                JPanel rPanel=new JPanel(new BorderLayout(0,8));rPanel.setOpaque(false);rPanel.add(new JLabel("Price rules (per night)"),BorderLayout.NORTH);rPanel.add(tablePanel(rules),BorderLayout.CENTER);rPanel.add(rTop,BorderLayout.SOUTH);
                JPanel pTop=new JPanel(new FlowLayout(FlowLayout.LEFT));pTop.setOpaque(false);pTop.add(addPromo);pTop.add(togglePromo);
                JPanel pPanel=new JPanel(new BorderLayout(0,8));pPanel.setOpaque(false);pPanel.add(new JLabel("Promotions (discount codes)"),BorderLayout.NORTH);pPanel.add(tablePanel(promos),BorderLayout.CENTER);pPanel.add(pTop,BorderLayout.SOUTH);
                JPanel salePanel=new JPanel(new FlowLayout(FlowLayout.LEFT));salePanel.setOpaque(false);salePanel.add(label("Room type"));salePanel.add(typeBox);salePanel.add(closeType);salePanel.add(openType);
                JPanel all=new JPanel(new GridLayout(3,1,0,16));all.setOpaque(false);all.add(rPanel);all.add(pPanel);all.add(salePanel);
                content.add(all,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void servicesCatalogPage(){
            page("Service catalog (Admin)","Create and manage the service menu. Only available services appear to customers. Prices here are used for customer requests and for staff manual charges.");
            try{
                JTable t=new JTable(query("SELECT id 'ID',name 'Service',category 'Category',description 'Description',price 'Price',service_hours 'Hours',available 'Available' FROM service_catalog ORDER BY category,name"));
                JButton addBtn=button("＋ Add service",TEAL),editBtn=button("Edit service",NAVY),toggleBtn=button("Activate / deactivate",NAVY);
                addBtn.addActionListener(e->serviceCatalogDialog(-1));
                editBtn.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a service first.");return;}serviceCatalogDialog(Integer.parseInt(t.getValueAt(row,0).toString()));});
                toggleBtn.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a service first.");return;}int id=Integer.parseInt(t.getValueAt(row,0).toString());try(PreparedStatement p=db.prepareStatement("UPDATE service_catalog SET available=CASE WHEN available=1 THEN 0 ELSE 1 END WHERE id=?")){p.setInt(1,id);p.executeUpdate();servicesCatalogPage();}catch(Exception ex){err(this,ex);}});
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);for(JButton b:new JButton[]{addBtn,editBtn,toggleBtn})top.add(b);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void serviceCatalogDialog(int svcId){try{
            String name="",cat="Other",desc="",hours="";double price=0;int avail=1;
            if(svcId>0)try(PreparedStatement q=db.prepareStatement("SELECT name,category,description,price,service_hours,available FROM service_catalog WHERE id=?")){q.setInt(1,svcId);try(ResultSet r=q.executeQuery()){if(r.next()){name=r.getString(1);cat=r.getString(2);desc=r.getString(3);price=r.getDouble(4);hours=r.getString(5);avail=r.getInt(6);}}}
            JTextField nameF=new JTextField(name),descF=new JTextField(desc),priceF=new JTextField(String.valueOf(price)),hoursF=new JTextField(hours);JComboBox<String> catBox=new JComboBox<>(new String[]{"Dining","Laundry","Spa","Transport","Other"});catBox.setSelectedItem(cat);JCheckBox availBox=new JCheckBox("Available to customers",avail==1);
            Object[] f={"Service name",nameF,"Category",catBox,"Description",descF,"Price (VND)",priceF,"Service hours (e.g. 06:00-10:00)",hoursF,availBox};
            if(JOptionPane.showConfirmDialog(this,f,svcId>0?"Edit service":"Add service",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            if(svcId>0){try(PreparedStatement p=db.prepareStatement("UPDATE service_catalog SET name=?,category=?,description=?,price=?,service_hours=?,available=? WHERE id=?")){p.setString(1,nameF.getText().trim());p.setString(2,(String)catBox.getSelectedItem());p.setString(3,descF.getText());p.setDouble(4,Double.parseDouble(priceF.getText()));p.setString(5,hoursF.getText());p.setInt(6,availBox.isSelected()?1:0);p.setInt(7,svcId);p.executeUpdate();}}
            else{try(PreparedStatement p=db.prepareStatement("INSERT INTO service_catalog(name,category,description,price,service_hours,available) VALUES(?,?,?,?,?,?)")){p.setString(1,nameF.getText().trim());p.setString(2,(String)catBox.getSelectedItem());p.setString(3,descF.getText());p.setDouble(4,Double.parseDouble(priceF.getText()));p.setString(5,hoursF.getText());p.setInt(6,availBox.isSelected()?1:0);p.executeUpdate();}}
            logAction("Update service catalog","Service "+nameF.getText());servicesCatalogPage();
        }catch(Exception e){err(this,e);}}
        void adminReviewsPage(){
            page("Reviews & replies (Admin)","Moderate reviews, reply to guests, and track average ratings. Hidden reviews are not shown to other guests, but the author still sees their own review.");
            try{
                double avgRoom=scalarD("SELECT COALESCE(AVG(rating),0) FROM reviews WHERE status='Approved'");double avgSvc=scalarD("SELECT COALESCE(AVG(service_rating),0) FROM reviews WHERE status='Approved' AND service_rating IS NOT NULL");long total=scalar("SELECT COUNT(*) FROM reviews");
                JLabel summary=new JLabel("Approved reviews: "+total+" · Average room rating: "+String.format(Locale.US,"%.1f",avgRoom)+" / 5 · Average service rating: "+String.format(Locale.US,"%.1f",avgSvc)+" / 5");summary.setFont(new Font("SansSerif",Font.BOLD,14));
                JTable t=new JTable(query("SELECT rv.id 'ID',u.name 'Guest',COALESCE(b.booking_code,'-') 'Booking',rv.rating 'Room rating',COALESCE(rv.service_rating,0) 'Service rating',rv.comment 'Comment',rv.status 'Status',COALESCE(rv.reply,'') 'Reply' FROM reviews rv JOIN users u ON u.id=rv.guest_id LEFT JOIN bookings b ON b.id=rv.booking_id ORDER BY rv.id DESC"));
                JButton replyBtn=button("Reply to review",TEAL),hideBtn=button("Hide / show",NAVY);
                replyBtn.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a review first.");return;}int id=Integer.parseInt(t.getValueAt(row,0).toString());JTextArea a=new JTextArea(4,45);a.setLineWrap(true);if(JOptionPane.showConfirmDialog(this,new JScrollPane(a),"Staff reply",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try{int bid=-1,guest=-1;try(PreparedStatement q=db.prepareStatement("SELECT booking_id,guest_id FROM reviews WHERE id=?")){q.setInt(1,id);try(ResultSet r=q.executeQuery()){if(r.next()){bid=r.getInt(1);guest=r.getInt(2);}}}try(PreparedStatement p=db.prepareStatement("UPDATE reviews SET reply=? WHERE id=?")){p.setString(1,a.getText());p.setInt(2,id);p.executeUpdate();}logAction("Reply to review","Review "+id);if(guest>0)createNotification(guest,bid>0?bid:null,"Review","Hotel replied to your review",a.getText());adminReviewsPage();}catch(Exception ex){err(this,ex);}});
                hideBtn.addActionListener(e->{int row=t.getSelectedRow();if(row<0){info(this,"Please select a review first.");return;}int id=Integer.parseInt(t.getValueAt(row,0).toString());try(PreparedStatement p=db.prepareStatement("UPDATE reviews SET status=CASE WHEN status='Approved' THEN 'Hidden' ELSE 'Approved' END WHERE id=?")){p.setInt(1,id);p.executeUpdate();adminReviewsPage();}catch(Exception ex){err(this,ex);}});
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);top.add(replyBtn);top.add(hideBtn);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(summary,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);holder.add(top,BorderLayout.SOUTH);content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void reportsPage(){
            page("Reports (Admin)","Revenue, occupancy, cancellation / no-show rates, average room rate, service revenue and payment methods for a date range. Revenue dates use the planned check-out date for checked-out bookings.");
            try{
                JTextField from=new JTextField(LocalDate.now().withDayOfMonth(1).toString()),to=new JTextField(LocalDate.now().toString());
                JTextArea out=new JTextArea(22,70);out.setEditable(false);
                JButton viewBtn=button("View report",TEAL),exportBtn=button("Save report as text",NAVY);
                Runnable runReport=()->{try{
                    LocalDate a=parseDate(from.getText()),b=parseDate(to.getText());if(a==null||b==null||b.isBefore(a))throw new IllegalArgumentException("Please enter valid From / To dates.");
                    long days=Math.max(1,ChronoUnit.DAYS.between(a,b)+1);long activeRooms=scalar("SELECT COUNT(*) FROM rooms WHERE is_active=1");
                    double revenue=0,roomRev=0,svcRev=0;long checkedOut=0,cancelled=0,noshow=0,totalBookings=0,roomNights=0;
                    try(PreparedStatement q=db.prepareStatement("SELECT b.id,b.room_id,b.checkin,b.checkout,COALESCE(b.actual_out,b.checkout),b.status,b.total FROM bookings b WHERE COALESCE(b.actual_out,b.checkout) BETWEEN ? AND ? OR (b.status IN ('Reserved','Occupied') AND b.checkin BETWEEN ? AND ?)")){q.setString(1,a.toString());q.setString(2,b.toString());q.setString(3,a.toString());q.setString(4,b.toString());try(ResultSet r=q.executeQuery()){while(r.next()){totalBookings++;String st=r.getString(6);if("Checked out".equals(st)){checkedOut++;revenue+=r.getDouble(7);roomRev+=roomAmount(r.getInt(2),LocalDate.parse(r.getString(3)),LocalDate.parse(r.getString(4)));svcRev+=servicesTotal(r.getInt(1));}else if("Cancelled".equals(st))cancelled++;else if("No-show".equals(st))noshow++;LocalDate ci=LocalDate.parse(r.getString(3)),co=LocalDate.parse(r.getString(4));LocalDate os=ci.isAfter(a)?ci:a,oe=co.isBefore(b.plusDays(1))?co:b.plusDays(1);if(oe.isAfter(os))roomNights+=ChronoUnit.DAYS.between(os,oe);}}}
                    double occ=activeRooms*days>0?(double)roomNights/(activeRooms*days)*100:0;double adr=roomNights>0?roomRev/roomNights:0;double cancelRate=totalBookings>0?(double)cancelled/totalBookings*100:0;double noshowRate=totalBookings>0?(double)noshow/totalBookings*100:0;
                    StringBuilder sb=new StringBuilder();
                    sb.append("Period: ").append(a).append(" to ").append(b).append(" (").append(days).append(" days, ").append(activeRooms).append(" active rooms)\n");
                    sb.append("Total revenue (checked-out bookings): ").append(money(revenue)).append("\n");
                    sb.append("Room revenue: ").append(money(roomRev)).append(" · Service revenue (catalog + laundry/minibar): ").append(money(svcRev)).append("\n");
                    sb.append("Occupancy: ").append(String.format(Locale.US,"%.1f",occ)).append("% (").append(roomNights).append(" room-nights sold)\n");
                    sb.append("Average daily rate (ADR): ").append(money(adr)).append("\n");
                    sb.append("Bookings in period: ").append(totalBookings).append(" · Checked out: ").append(checkedOut).append(" · Cancelled: ").append(cancelled).append(" (").append(String.format(Locale.US,"%.1f",cancelRate)).append("%) · No-show: ").append(noshow).append(" (").append(String.format(Locale.US,"%.1f",noshowRate)).append("%)\n\n");
                    sb.append("Revenue by payment method (completed payments in period, refunds deducted):\n");
                    try(PreparedStatement q=db.prepareStatement("SELECT method, COALESCE(SUM(CASE WHEN type='Refund' THEN -amount ELSE amount END),0) FROM payments WHERE status='Completed' AND substr(created_at,1,10) BETWEEN ? AND ? GROUP BY method")){q.setString(1,a.toString());q.setString(2,b.toString());try(ResultSet r=q.executeQuery()){while(r.next())sb.append("  ").append(r.getString(1)).append(": ").append(money(r.getDouble(2))).append("\n");}}
                    sb.append("\nRevenue by room type (checked-out bookings):\n");
                    try(PreparedStatement q=db.prepareStatement("SELECT r.type, COUNT(*), COALESCE(SUM(b.total),0) FROM bookings b JOIN rooms r ON r.id=b.room_id WHERE b.status='Checked out' AND COALESCE(b.actual_out,b.checkout) BETWEEN ? AND ? GROUP BY r.type")){q.setString(1,a.toString());q.setString(2,b.toString());try(ResultSet r=q.executeQuery()){while(r.next())sb.append("  ").append(r.getString(1)).append(": ").append(r.getInt(2)).append(" bookings · ").append(money(r.getDouble(3))).append("\n");}}
                    out.setText(sb.toString());
                }catch(Exception ex){err(this,ex);}};
                viewBtn.addActionListener(e->runReport.run());
                exportBtn.addActionListener(e->{runReport.run();saveTextFile("report_"+from.getText()+"_to_"+to.getText()+".txt",out.getText());});
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);top.add(label("From"));top.add(from);top.add(label("To"));top.add(to);top.add(viewBtn);top.add(exportBtn);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(new JScrollPane(out),BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);
                runReport.run();
            }catch(Exception e){err(this,e);}refresh();
        }
        void systemConfigPage(){
            page("System configuration (Admin)","Hotel details, check-in / check-out times, cancellation policy, currency, tax and deposit. Changes are recorded in the audit log below. Backups are in Export & backup.");
            try{
                JTextField nameF=new JTextField(setting("hotel_name","HotelFlow")),phoneF=new JTextField(setting("hotel_phone","")),emailF=new JTextField(setting("hotel_email","")),addrF=new JTextField(setting("hotel_address",""));
                JTextField ciF=new JTextField(setting("checkin_time","14:00")),coF=new JTextField(setting("checkout_time","12:00")),currF=new JTextField(setting("currency","VND")),taxF=new JTextField(setting("tax_rate","0")),depF=new JTextField(setting("deposit_percent","30")),freeF=new JTextField(setting("cancellation_free_days","2")),lateF=new JTextField(setting("late_cancel_refund_percent","50"));
                JTextArea policyA=new JTextArea(setting("cancellation_policy",""),3,50);policyA.setLineWrap(true);
                JPanel form=new JPanel();form.setBackground(Color.WHITE);form.setBorder(new EmptyBorder(20,20,20,20));form.setLayout(new BoxLayout(form,BoxLayout.Y_AXIS));
                String[][] fields={{"Hotel name",null},{"Phone",null},{"Email",null},{"Address",null},{"Check-in time (HH:MM)",null},{"Check-out time (HH:MM)",null},{"Currency",null},{"Tax rate (%)",null},{"Deposit (%)",null},{"Cancellation free days",null},{"Late cancel refund (%)",null}};
                JTextField[] inputs={nameF,phoneF,emailF,addrF,ciF,coF,currF,taxF,depF,freeF,lateF};
                for(int i=0;i<fields.length;i++){form.add(label(fields[i][0]));form.add(inputs[i]);form.add(Box.createVerticalStrut(8));}
                form.add(label("Cancellation policy (shown to customers)"));form.add(new JScrollPane(policyA));form.add(Box.createVerticalStrut(14));
                JButton save=button("Save configuration",TEAL),backupBtn=button("Go to Export & backup",NAVY);
                save.addActionListener(e->{try{
                    double tax=Double.parseDouble(taxF.getText());if(tax<0||tax>100)throw new IllegalArgumentException("Tax rate must be between 0 and 100.");
                    setSetting("hotel_name",nameF.getText().trim());setSetting("hotel_phone",phoneF.getText().trim());setSetting("hotel_email",emailF.getText().trim());setSetting("hotel_address",addrF.getText().trim());setSetting("checkin_time",ciF.getText().trim());setSetting("checkout_time",coF.getText().trim());setSetting("currency",currF.getText().trim());setSetting("tax_rate",taxF.getText().trim());setSetting("deposit_percent",depF.getText().trim());setSetting("cancellation_free_days",freeF.getText().trim());setSetting("late_cancel_refund_percent",lateF.getText().trim());setSetting("cancellation_policy",policyA.getText().trim());
                    logAction("Update system configuration","Tax "+taxF.getText()+"%, deposit "+depF.getText()+"%");info(this,"Configuration saved.");
                }catch(Exception ex){err(this,ex);}});
                backupBtn.addActionListener(e->exportBackup());
                JPanel btns=new JPanel(new FlowLayout(FlowLayout.LEFT));btns.setOpaque(false);btns.add(save);btns.add(backupBtn);form.add(btns);
                JTable audit=new JTable(query("SELECT id 'ID',created_at 'Time',username 'User',action 'Action',detail 'Detail' FROM audit_log ORDER BY id DESC LIMIT 100"));
                JPanel holder=new JPanel(new BorderLayout(0,14));holder.setOpaque(false);holder.add(form,BorderLayout.NORTH);holder.add(new JLabel("Audit log (latest 100 actions)"),BorderLayout.CENTER);
                JPanel all=new JPanel(new BorderLayout(0,14));all.setOpaque(false);all.add(holder,BorderLayout.NORTH);all.add(tablePanel(audit),BorderLayout.CENTER);
                content.add(all,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void housekeepingPage(){
            page("Housekeeping & maintenance","Rooms that need cleaning or repair, and the tasks assigned to staff. Completing a cleaning task can set the room back to Available.");
            try{
                JTable roomsT=new JTable(query("SELECT id 'ID',number 'Room',type 'Type',status 'Status' FROM rooms WHERE status IN ('Cleaning','Maintenance','Closed') ORDER BY number"));
                JTable tasks=new JTable(query("SELECT t.id 'ID',r.number 'Room',t.task_type 'Task',t.assigned_to 'Assigned to',t.status 'Status',t.note 'Note' FROM housekeeping_tasks t JOIN rooms r ON r.id=t.room_id ORDER BY t.id DESC LIMIT 100"));
                JButton createTask=button("＋ Create task",TEAL),startTask=button("Start task",NAVY),completeTask=button("Complete task",TEAL),markClean=button("Mark room cleaned → Available",NAVY);
                createTask.addActionListener(e->{try{
                    JComboBox<String> roomBox=new JComboBox<>();Map<String,Integer> roomIds=new HashMap<>();
                    try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,number FROM rooms ORDER BY number")){while(r.next()){String n=r.getString(2);roomBox.addItem(n);roomIds.put(n,r.getInt(1));}}
                    JComboBox<String> typeBox=new JComboBox<>(new String[]{"Cleaning","Maintenance"});
                    JComboBox<String> staffBox=new JComboBox<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT name FROM users WHERE role IN ('ADMIN','RECEPTIONIST') ORDER BY name")){while(r.next())staffBox.addItem(r.getString(1));}
                    JTextField note=new JTextField();
                    Object[] f={"Room",roomBox,"Task type",typeBox,"Assigned to",staffBox,"Note",note};
                    if(JOptionPane.showConfirmDialog(this,f,"Create housekeeping task",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                    int rid=roomIds.get(roomBox.getSelectedItem());
                    try(PreparedStatement p=db.prepareStatement("INSERT INTO housekeeping_tasks(room_id,task_type,assigned_to,status,note) VALUES(?,?,?,?,?)")){p.setInt(1,rid);p.setString(2,(String)typeBox.getSelectedItem());p.setString(3,staffBox.getSelectedItem()==null?"":(String)staffBox.getSelectedItem());p.setString(4,"Pending");p.setString(5,note.getText());p.executeUpdate();}
                    try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET status=? WHERE id=? AND status NOT IN ('Occupied')")){p.setString(1,(String)typeBox.getSelectedItem());p.setInt(2,rid);p.executeUpdate();}
                    housekeepingPage();
                }catch(Exception ex){err(this,ex);}});
                startTask.addActionListener(e->{int row=tasks.getSelectedRow();if(row<0){info(this,"Please select a task first.");return;}int id=Integer.parseInt(tasks.getValueAt(row,0).toString());try(PreparedStatement p=db.prepareStatement("UPDATE housekeeping_tasks SET status='In progress' WHERE id=? AND status='Pending'")){p.setInt(1,id);p.executeUpdate();housekeepingPage();}catch(Exception ex){err(this,ex);}});
                completeTask.addActionListener(e->{int row=tasks.getSelectedRow();if(row<0){info(this,"Please select a task first.");return;}int id=Integer.parseInt(tasks.getValueAt(row,0).toString());try{
                    int rid=0;String type="";
                    try(PreparedStatement q=db.prepareStatement("SELECT room_id,task_type FROM housekeeping_tasks WHERE id=?")){q.setInt(1,id);try(ResultSet r=q.executeQuery()){if(r.next()){rid=r.getInt(1);type=r.getString(2);}}}
                    try(PreparedStatement p=db.prepareStatement("UPDATE housekeeping_tasks SET status='Done' WHERE id=?")){p.setInt(1,id);p.executeUpdate();}
                    if(JOptionPane.showConfirmDialog(this,"Task done. Set room "+roomNumberOf(rid)+" back to Available?","Room ready?",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET status='Available' WHERE id=?")){p.setInt(1,rid);p.executeUpdate();}}
                    housekeepingPage();
                }catch(Exception ex){err(this,ex);}});
                markClean.addActionListener(e->{int row=roomsT.getSelectedRow();if(row<0){info(this,"Please select a room first.");return;}int id=Integer.parseInt(roomsT.getValueAt(row,0).toString());try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET status='Available' WHERE id=? AND status='Cleaning'")){p.setInt(1,id);if(p.executeUpdate()>0){info(this,"Room is now Available.");housekeepingPage();}else info(this,"Only a Cleaning room can be marked cleaned here. Use a task for Maintenance.");}catch(Exception ex){err(this,ex);}});
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);for(JButton b:new JButton[]{createTask,startTask,completeTask,markClean})top.add(b);
                JPanel rPanel=new JPanel(new BorderLayout(0,8));rPanel.setOpaque(false);rPanel.add(new JLabel("Rooms needing attention"),BorderLayout.NORTH);rPanel.add(tablePanel(roomsT),BorderLayout.CENTER);
                JPanel tPanel=new JPanel(new BorderLayout(0,8));tPanel.setOpaque(false);tPanel.add(new JLabel("Tasks"),BorderLayout.NORTH);tPanel.add(tablePanel(tasks),BorderLayout.CENTER);
                JPanel all=new JPanel(new GridLayout(2,1,0,16));all.setOpaque(false);all.add(rPanel);all.add(tPanel);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(all,BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}refresh();
        }
        void refresh(){content.revalidate();content.repaint();}
    }
    static String roleName(String r){return switch(r){case "ADMIN"->"Admin";case "RECEPTIONIST"->"Receptionist";default->"Customer";};}
    static DefaultTableModel query(String sql)throws SQLException{DefaultTableModel m=new DefaultTableModel(){public boolean isCellEditable(int r,int c){return false;}};try(Statement s=db.createStatement();ResultSet r=s.executeQuery(sql)){ResultSetMetaData md=r.getMetaData();for(int i=1;i<=md.getColumnCount();i++)m.addColumn(md.getColumnLabel(i));while(r.next()){Object[] row=new Object[md.getColumnCount()];for(int i=0;i<row.length;i++)row[i]=r.getObject(i+1);m.addRow(row);}}return m;}
    static JPanel tablePanel(JTable table){table.setRowHeight(30);table.setFont(new Font("SansSerif",Font.PLAIN,13));table.getTableHeader().setFont(new Font("SansSerif",Font.BOLD,12));table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);JScrollPane s=new JScrollPane(table);s.setBorder(BorderFactory.createLineBorder(new Color(227,232,238)));JPanel p=new JPanel(new BorderLayout());p.setOpaque(false);p.add(s,BorderLayout.CENTER);return p;}
    static JTable findTable(Container c){for(Component x:c.getComponents()){if(x instanceof JTable t)return t;if(x instanceof Container k){JTable t=findTable(k);if(t!=null)return t;}}return null;}
    static long scalar(String q)throws SQLException{try(Statement s=db.createStatement();ResultSet r=s.executeQuery(q)){return r.next()?r.getLong(1):0;}}static double scalarD(String q)throws SQLException{try(Statement s=db.createStatement();ResultSet r=s.executeQuery(q)){return r.next()?r.getDouble(1):0;}}
    static class RevenueChart extends JPanel{
        final JTable data;RevenueChart(JTable t){data=t;setBackground(Color.WHITE);setPreferredSize(new Dimension(500,280));}
        protected void paintComponent(Graphics gg){super.paintComponent(gg);Graphics2D g=(Graphics2D)gg.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int left=65,right=22,top=18,bottom=44,w=getWidth()-left-right,h=getHeight()-top-bottom;int n=data.getRowCount();double max=1;for(int i=0;i<n;i++)try{max=Math.max(max,Double.parseDouble(data.getValueAt(i,1).toString()));}catch(Exception ignored){}g.setColor(new Color(231,236,240));for(int i=0;i<=4;i++){int y=top+h*i/4;g.drawLine(left,y,left+w,y);g.setColor(Color.GRAY);g.drawString(money(max*(4-i)/4).replace(" ₫",""),4,y+5);g.setColor(new Color(231,236,240));}int bw=Math.max(8,Math.min(42,w/Math.max(1,n)-8));for(int i=0;i<n;i++){int x=left+(i+1)*w/(n+1)-bw/2;double v=Double.parseDouble(data.getValueAt(i,1).toString());int bh=(int)(h*v/max);g.setColor(TEAL);g.fillRoundRect(x,top+h-bh,bw,bh,8,8);g.setColor(INK);String label=data.getValueAt(i,0).toString();if(label.length()>7)label=label.substring(5);g.drawString(label,x-5,top+h+19);}g.dispose();}
    }
}

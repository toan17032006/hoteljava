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
    record Room(int id,String number,String type,double price,String status){}
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
        seed("admin","admin123","Administrator","ADMIN","0900000001");seed("le_tan","123456","Receptionist","RECEPTIONIST","0900000002");seed("khach","123456","Sample customer","CUSTOMER","0900000003");
        String[][] rooms={{"101","Standard","550000"},{"102","Standard","550000"},{"201","Deluxe","900000"},{"202","Deluxe","900000"},{"301","VIP","1600000"},{"302","VIP","1600000"}};
        for(String[] r:rooms)try(PreparedStatement p=db.prepareStatement("INSERT OR IGNORE INTO rooms(number,type,price) VALUES(?,?,?)")){p.setString(1,r[0]);p.setString(2,r[1]);p.setDouble(3,Double.parseDouble(r[2]));p.executeUpdate();}
    }
    static void seed(String u,String pw,String n,String role,String phone)throws Exception{try(PreparedStatement p=db.prepareStatement("INSERT OR IGNORE INTO users(name,username,password,role,phone) VALUES(?,?,?,?,?)")){p.setString(1,n);p.setString(2,u);p.setString(3,encodePassword(pw));p.setString(4,role);p.setString(5,phone);p.executeUpdate();}}

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
            go.addActionListener(e->{try{User u=authenticate(name.getText().trim(),new String(pass.getPassword()));if(u!=null){user=u;dispose();new Dashboard().setVisible(true);}else info(this,"Username or password is incorrect.");}catch(Exception ex){err(this,ex);}});
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
            if(user.role().equals("ADMIN")||user.role().equals("RECEPTIONIST")){
                addNav(side,"Rooms",()->rooms(false));addNav(side,"Bookings & guests",this::bookings);addNav(side,"Cleaning",this::cleaning);addNav(side,"Export & backup",this::exportBackup);addNav(side,"Invoices",this::billing);
            }
            if(user.role().equals("ADMIN")) {addNav(side,"Staff",this::staff);addNav(side,"Revenue",this::analytics);}
            if(user.role().equals("CUSTOMER")){addNav(side,"Find a room",()->rooms(true));addNav(side,"My bookings",this::myBookings);addNav(side,"Reviews",this::review);}
            side.add(Box.createVerticalGlue());JButton logout=button("Sign out",new Color(72,88,106));logout.setAlignmentX(Component.LEFT_ALIGNMENT);logout.addActionListener(e->{dispose();new Login().setVisible(true);});side.add(logout);
            add(side,BorderLayout.WEST);content.setBackground(BG);content.setBorder(new EmptyBorder(27,30,28,30));add(content,BorderLayout.CENTER);home();
        }
        void addNav(JPanel p,String name,Runnable action){JButton b=new JButton(name);styleButton(b,NAVY,Color.WHITE);b.setMaximumSize(new Dimension(205,42));b.setAlignmentX(Component.LEFT_ALIGNMENT);b.setHorizontalAlignment(SwingConstants.LEFT);b.setFont(new Font("SansSerif",Font.PLAIN,14));b.setBorder(new EmptyBorder(11,12,11,8));b.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseEntered(java.awt.event.MouseEvent e){b.setBackground(new Color(39,61,83));}public void mouseExited(java.awt.event.MouseEvent e){b.setBackground(NAVY);}});b.addActionListener(e->action.run());p.add(b);}
        void page(String title,String subtitle){content.removeAll();JPanel head=new JPanel();head.setOpaque(false);head.setLayout(new BoxLayout(head,BoxLayout.Y_AXIS));JLabel t=new JLabel(title);t.setFont(new Font("SansSerif",Font.BOLD,26));t.setForeground(INK);JLabel st=new JLabel(subtitle);st.setForeground(Color.GRAY);head.add(t);head.add(Box.createVerticalStrut(5));head.add(st);head.add(Box.createVerticalStrut(22));content.add(head,BorderLayout.NORTH);}
        void home(){page("Welcome, "+user.name(),"Hotel activity today · "+LocalDate.now());JPanel body=new JPanel(new BorderLayout(0,18));body.setOpaque(false);JPanel cards=new JPanel(new GridLayout(1,3,14,0));cards.setOpaque(false);try{cards.add(card("TOTAL ROOMS",String.valueOf(scalar("SELECT COUNT(*) FROM rooms")),"Rooms in the system"));cards.add(card("OCCUPIED ROOMS",String.valueOf(scalar("SELECT COUNT(*) FROM rooms WHERE status='Occupied'")),"Rooms currently occupied"));if(user.role().equals("CUSTOMER")){cards.add(card("MY BOOKINGS",String.valueOf(scalar("SELECT COUNT(*) FROM bookings WHERE guest_id="+user.id())),"Your bookings"));}else{cards.add(card("REVENUE",money(scalarD("SELECT COALESCE(SUM(total),0) FROM bookings WHERE status='Checked out'")),"Recorded revenue"));} }catch(Exception e){err(this,e);}body.add(cards,BorderLayout.NORTH);
            JPanel bottom=new JPanel(new BorderLayout());bottom.setOpaque(false);boolean customer=user.role().equals("CUSTOMER");JLabel recent=new JLabel(customer?"Your recent bookings":"Recent bookings");recent.setFont(new Font("SansSerif",Font.BOLD,17));bottom.add(recent,BorderLayout.NORTH);try{String sql="SELECT b.id 'ID',u.name 'Customer',r.number 'Room',b.checkin 'Check-in',b.checkout 'Check-out',b.status 'Status' FROM bookings b JOIN users u ON u.id=b.guest_id JOIN rooms r ON r.id=b.room_id"+(customer?" WHERE b.guest_id="+user.id():"")+" ORDER BY b.id DESC LIMIT 8";bottom.add(tablePanel(new JTable(query(sql))),BorderLayout.CENTER);}catch(Exception e){err(this,e);}body.add(bottom,BorderLayout.CENTER);content.add(body,BorderLayout.CENTER);refresh();}
        JPanel card(String title,String val,String caption){JPanel p=new JPanel();p.setBackground(Color.WHITE);p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(230,234,239)),new EmptyBorder(16,18,16,18)));p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));JLabel a=new JLabel(title);a.setForeground(Color.GRAY);a.setFont(new Font("SansSerif",Font.BOLD,11));JLabel b=new JLabel(val);b.setFont(new Font("SansSerif",Font.BOLD,22));b.setForeground(TEAL);JLabel c=new JLabel(caption);c.setForeground(Color.GRAY);p.add(a);p.add(Box.createVerticalStrut(10));p.add(b);p.add(Box.createVerticalStrut(6));p.add(c);return p;}
        void rooms(boolean customer){page(customer?"Find available rooms":"Room management",customer?"Choose a room and submit a booking request.":"Manage room types, rates, and occupancy status.");try{JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));actions.setOpaque(false);if(user.role().equals("ADMIN")){JButton add=button("＋ Add room",TEAL);add.addActionListener(e->roomDialog(null));actions.add(add);}JTextField search=new JTextField(18);search.setToolTipText("Search by room number or type");JButton find=button("Search",NAVY);actions.add(search);actions.add(find);if(customer){JButton book=button("Book selected room",TEAL);actions.add(book);book.addActionListener(e->{JTable t=findTable(content);if(t.getSelectedRow()<0){info(this,"Please select a room first.");return;}int id=Integer.parseInt(t.getValueAt(t.getSelectedRow(),0).toString());bookingDialog(id);});}
            JTable t=new JTable();Runnable reload=()->{try{String term=search.getText().trim();DefaultTableModel m=query("SELECT id 'ID',number 'Room number',type 'Type',price 'Rate / night',status 'Status' FROM rooms WHERE status='Available' AND (number LIKE '%"+term.replace("'","''")+"%' OR type LIKE '%"+term.replace("'","''")+"%') ORDER BY number");t.setModel(m);}catch(Exception ex){err(this,ex);}};reload.run();find.addActionListener(e->reload.run());JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(actions,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);if(!customer&&user.role().equals("ADMIN")){JButton edit=button("Edit selected room",NAVY);edit.addActionListener(e->{if(t.getSelectedRow()<0)return;roomDialog(new Room((int)t.getValueAt(t.getSelectedRow(),0),t.getValueAt(t.getSelectedRow(),1).toString(),t.getValueAt(t.getSelectedRow(),2).toString(),Double.parseDouble(t.getValueAt(t.getSelectedRow(),3).toString()),t.getValueAt(t.getSelectedRow(),4).toString()));});actions.add(edit);}content.add(holder,BorderLayout.CENTER);
            // Admin/reception staff need to see occupied and cleaning rooms too.
            if(!customer){try{t.setModel(query("SELECT id 'ID',number 'Room number',type 'Type',price 'Rate / night',status 'Status' FROM rooms ORDER BY number"));}catch(Exception ignored){}}
        }catch(Exception e){err(this,e);}refresh();}
        void roomDialog(Room room){JTextField number=new JTextField(room==null?"":room.number()),price=new JTextField(room==null?"550000":String.valueOf(room.price()));JComboBox<String> type=new JComboBox<>(new String[]{"Standard","Deluxe","VIP"}),status=new JComboBox<>(new String[]{"Available","Occupied","Cleaning"});if(room!=null){type.setSelectedItem(room.type());status.setSelectedItem(room.status());}Object[] fields={"Room number",number,"Room type",type,"Rate / night (VND)",price,"Status",status};if(JOptionPane.showConfirmDialog(this,fields,room==null?"Add room":"Update room",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try(PreparedStatement p=db.prepareStatement(room==null?"INSERT INTO rooms(number,type,price,status) VALUES(?,?,?,?)":"UPDATE rooms SET number=?,type=?,price=?,status=? WHERE id=?")){p.setString(1,number.getText().trim());p.setString(2,(String)type.getSelectedItem());p.setDouble(3,Double.parseDouble(price.getText()));p.setString(4,(String)status.getSelectedItem());if(room!=null)p.setInt(5,room.id());p.executeUpdate();rooms(false);}catch(Exception e){err(this,e);}}
        void bookings(){
            page("Bookings & stays","Create bookings, check guests in, add services, check guests out, cancel or extend a stay. Use Search and Status to find a booking fast.");
            try{
                JTable t=new JTable();
                JTextField search=new JTextField(16);
                search.setToolTipText("Search by guest name or room number");
                JComboBox<String> statusFilter=new JComboBox<>(new String[]{"All","Reserved","Occupied","Checked out","Cancelled"});
                JButton find=button("Search",NAVY);
                Runnable reload=()->{
                    try{
                        String term=search.getText().trim().replace("'","''");
                        String status=(String)statusFilter.getSelectedItem();
                        String sql="SELECT b.id 'ID',u.name 'Guest',r.number 'Room',b.checkin 'Check-in',b.checkout 'Expected check-out',b.status 'Status',b.laundry 'Laundry',b.minibar 'Mini bar',b.discount 'Discount %',b.total 'Subtotal' FROM bookings b JOIN users u ON u.id=b.guest_id JOIN rooms r ON r.id=b.room_id WHERE (u.name LIKE '%"+term+"%' OR r.number LIKE '%"+term+"%')";
                        if(!"All".equals(status)) sql+=" AND b.status='"+status+"'";
                        sql+=" ORDER BY b.id DESC";
                        t.setModel(query(sql));
                    }catch(Exception ex){err(this,ex);}
                };
                reload.run();
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);
                JButton create=button("＋ New booking",TEAL),ci=button("Check-in",NAVY),svc=button("＋ Add services / discount",NAVY),co=button("Check out & invoice",TEAL),cancel=button("Cancel booking",new Color(176,58,58)),extend=button("Extend stay",NAVY);
                for(JButton b:new JButton[]{create,ci,svc,co,cancel,extend}) top.add(b);
                create.addActionListener(e->bookingDialog(-1));
                ci.addActionListener(e->changeBooking(t,"checkin"));
                svc.addActionListener(e->serviceDialog(t));
                co.addActionListener(e->checkout(t));
                cancel.addActionListener(e->cancelBooking(t));
                extend.addActionListener(e->extendBooking(t));
                find.addActionListener(e->reload.run());
                search.addActionListener(e->reload.run());
                statusFilter.addActionListener(e->reload.run());
                JPanel filter=new JPanel(new FlowLayout(FlowLayout.LEFT));filter.setOpaque(false);
                filter.add(label("Search"));filter.add(search);filter.add(label("Status"));filter.add(statusFilter);filter.add(find);
                JPanel north=new JPanel(new BorderLayout(0,8));north.setOpaque(false);north.add(top,BorderLayout.NORTH);north.add(filter,BorderLayout.SOUTH);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(north,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);
                content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}
            refresh();
        }
        void cancelBooking(JTable t){
            int id=selected(t);if(id<0)return;
            try{
                String status=null;
                try(PreparedStatement p=db.prepareStatement("SELECT status FROM bookings WHERE id=?")){p.setInt(1,id);try(ResultSet r=p.executeQuery()){if(r.next()) status=r.getString(1);}}
                if(status==null){info(this,"Booking not found.");return;}
                if(!"Reserved".equals(status)){info(this,"Only a Reserved booking can be cancelled. Checked-in or checked-out bookings cannot be cancelled here.");return;}
                if(JOptionPane.showConfirmDialog(this,"Cancel this booking? The room will stay available for other guests.","Cancel booking",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET status='Cancelled' WHERE id=? AND status='Reserved'")){p.setInt(1,id);p.executeUpdate();}
                info(this,"Booking cancelled.");bookings();
            }catch(Exception e){err(this,e);}
        }
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
                info(this,"Stay extended to "+newOut+".");bookings();
            }catch(Exception e){err(this,e);}
        }
        int selected(JTable t){if(t.getSelectedRow()<0){info(this,"Please select a booking first.");return -1;}return Integer.parseInt(t.getValueAt(t.getSelectedRow(),0).toString());}
        void changeBooking(JTable t,String action){int id=selected(t);if(id<0)return;try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET status='Occupied' WHERE id=? AND status='Reserved'")){p.setInt(1,id);if(p.executeUpdate()>0)try(PreparedStatement q=db.prepareStatement("UPDATE rooms SET status='Occupied' WHERE id=(SELECT room_id FROM bookings WHERE id=?)")){q.setInt(1,id);q.executeUpdate();info(this,"Check-in completed.");bookings();}else info(this,"This booking is not eligible for check-in.");}catch(Exception e){err(this,e);}}
        void bookingDialog(int roomId){try{JComboBox<String> guests=new JComboBox<>();Map<String,Integer> guestIds=new HashMap<>();if(user.role().equals("CUSTOMER")){String n=user.name()+" (#"+user.id()+")";guests.addItem(n);guestIds.put(n,user.id());}else try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,name FROM users WHERE role='CUSTOMER' ORDER BY name")){while(r.next()){String n=r.getString(2)+" (#"+r.getInt(1)+")";guests.addItem(n);guestIds.put(n,r.getInt(1));}}JComboBox<String> rooms=new JComboBox<>();Map<String,Integer> roomIds=new HashMap<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,number,type,price FROM rooms WHERE status='Available' ORDER BY number")){while(r.next()){String n=r.getString(2)+" · "+r.getString(3)+" · "+money(r.getDouble(4));rooms.addItem(n);roomIds.put(n,r.getInt(1));}}if(roomId>0)for(String n:roomIds.keySet())if(roomIds.get(n)==roomId)rooms.setSelectedItem(n);JTextField in=new JTextField(LocalDate.now().toString()),out=new JTextField(LocalDate.now().plusDays(1).toString());Object[] f={"Customer",guests,"Available room",rooms,"Check-in date (YYYY-MM-DD)",in,"Check-out date (YYYY-MM-DD)",out};if(JOptionPane.showConfirmDialog(this,f,"Create booking",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;if(guests.getSelectedItem()==null||rooms.getSelectedItem()==null){info(this,"At least one guest and one available room are required.");return;}LocalDate a=LocalDate.parse(in.getText()),b=LocalDate.parse(out.getText());if(!b.isAfter(a))throw new IllegalArgumentException("Check-out date must be after check-in date.");int selectedRoom=roomIds.get(rooms.getSelectedItem());try(PreparedStatement check=db.prepareStatement("SELECT COUNT(*) FROM bookings WHERE room_id=? AND status IN ('Reserved','Occupied') AND checkin < ? AND checkout > ?")){check.setInt(1,selectedRoom);check.setString(2,b.toString());check.setString(3,a.toString());try(ResultSet r=check.executeQuery()){if(r.next()&&r.getInt(1)>0)throw new IllegalArgumentException("The room is already booked for these dates.");}}try(PreparedStatement p=db.prepareStatement("INSERT INTO bookings(guest_id,room_id,checkin,checkout) VALUES(?,?,?,?)")){p.setInt(1,guestIds.get(guests.getSelectedItem()));p.setInt(2,selectedRoom);p.setString(3,a.toString());p.setString(4,b.toString());p.executeUpdate();info(this,"Booking created.");if(user.role().equals("CUSTOMER"))myBookings();else bookings();}}catch(Exception e){err(this,e);}}
        void serviceDialog(JTable t){int id=selected(t);if(id<0)return;JTextField laundry=new JTextField("0"),mini=new JTextField("0"),discount=new JTextField("0");Object[] f={"Laundry charge (VND)",laundry,"Minibar / food (VND)",mini,"Discount (%)",discount};if(JOptionPane.showConfirmDialog(this,f,"Additional services & discount",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try{double disc=Double.parseDouble(discount.getText());if(disc<0||disc>100)throw new IllegalArgumentException("Discount must be between 0 and 100.");try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET laundry=?,minibar=?,discount=? WHERE id=? AND status='Occupied'")){p.setDouble(1,Double.parseDouble(laundry.getText()));p.setDouble(2,Double.parseDouble(mini.getText()));p.setDouble(3,disc);p.setInt(4,id);if(p.executeUpdate()==0)info(this,"Services can only be added for checked-in guests.");else bookings();}}catch(Exception e){err(this,e);}}
        String invoice(int id)throws Exception{try(PreparedStatement p=db.prepareStatement("SELECT b.id,u.name,u.phone,r.number,r.type,r.price,b.checkin,COALESCE(b.actual_out,b.checkout),b.laundry,b.minibar,b.total,b.discount,b.status FROM bookings b JOIN users u ON u.id=b.guest_id JOIN rooms r ON r.id=b.room_id WHERE b.id=?")){p.setInt(1,id);try(ResultSet r=p.executeQuery()){if(!r.next())throw new Exception("Booking not found.");long days=Math.max(1,ChronoUnit.DAYS.between(LocalDate.parse(r.getString(7)),LocalDate.parse(r.getString(8))));double roomCost=days*r.getDouble(6),laundry=r.getDouble(9),mini=r.getDouble(10),subtotal=roomCost+laundry+mini,discPct=r.getDouble(12),discAmt=subtotal*discPct/100,total=subtotal-discAmt;return "HOTELFLOW · INVOICE #"+id+"\nCustomer: "+r.getString(2)+"\nPhone: "+r.getString(3)+"\nRoom: "+r.getString(4)+" ("+r.getString(5)+")\nStay: "+r.getString(7)+" — "+r.getString(8)+" ("+days+" nights)\nRoom charge: "+money(roomCost)+"\nLaundry: "+money(laundry)+"\nMinibar / food: "+money(mini)+"\nSubtotal: "+money(subtotal)+"\nDiscount ("+discPct+"%): -"+money(discAmt)+"\nTOTAL DUE: "+money(total)+"\nStatus: "+r.getString(13)+"\nThank you for staying with us!";}}}
        void checkout(JTable t){int id=selected(t);if(id<0)return;try{String bill=invoice(id);if(JOptionPane.showConfirmDialog(this,bill+"\n\nConfirm check-out and create invoice?","Payment",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try(PreparedStatement p=db.prepareStatement("UPDATE bookings SET status='Checked out',actual_out=?,total=? WHERE id=? AND status='Occupied'")){String now=LocalDate.now().toString();p.setString(1,now);long days=Math.max(1,ChronoUnit.DAYS.between(getDate(id,"checkin"),LocalDate.parse(now)));double sub=days*getPrice(id)+getServices(id);double disc=getDiscount(id);p.setDouble(2,sub-sub*disc/100);p.setInt(3,id);if(p.executeUpdate()==0){info(this,"The guest must check in before checking out.");return;}}try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET status='Cleaning' WHERE id=(SELECT room_id FROM bookings WHERE id=?)")){p.setInt(1,id);p.executeUpdate();}info(this,"Check-out completed. The room is now marked for cleaning.");billing();}catch(Exception e){err(this,e);}}
        LocalDate getDate(int id,String col)throws Exception{try(PreparedStatement p=db.prepareStatement("SELECT "+col+" FROM bookings WHERE id=?")){p.setInt(1,id);try(ResultSet r=p.executeQuery()){r.next();return LocalDate.parse(r.getString(1));}}}double getPrice(int id)throws Exception{try(PreparedStatement p=db.prepareStatement("SELECT r.price FROM bookings b JOIN rooms r ON b.room_id=r.id WHERE b.id=?")){p.setInt(1,id);try(ResultSet r=p.executeQuery()){r.next();return r.getDouble(1);}}}double getServices(int id)throws Exception{try(PreparedStatement p=db.prepareStatement("SELECT laundry+minibar FROM bookings WHERE id=?")){p.setInt(1,id);try(ResultSet r=p.executeQuery()){r.next();return r.getDouble(1);}}}
        double getDiscount(int id)throws Exception{try(PreparedStatement p=db.prepareStatement("SELECT COALESCE(discount,0) FROM bookings WHERE id=?")){p.setInt(1,id);try(ResultSet r=p.executeQuery()){r.next();return r.getDouble(1);}}}
        void billing(){page("Invoices","Review payments and export invoices as PDF or text.");try{JTable t=new JTable(query("SELECT b.id 'ID',u.name 'Customer',r.number 'Room',b.checkin 'Check-in',COALESCE(b.actual_out,b.checkout) 'Check-out',b.total 'Total',b.status 'Status' FROM bookings b JOIN users u ON u.id=b.guest_id JOIN rooms r ON r.id=b.room_id ORDER BY b.id DESC"));JButton txt=button("Export TXT",NAVY),pdf=button("Export PDF",TEAL);JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);top.add(txt);top.add(pdf);txt.addActionListener(e->export(t,false));pdf.addActionListener(e->export(t,true));JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);}catch(Exception e){err(this,e);}refresh();}
        void export(JTable t,boolean pdf){int id=selected(t);if(id<0)return;try{String text=invoice(id);JFileChooser fc=new JFileChooser();fc.setSelectedFile(new File("Invoice_"+id+(pdf?".pdf":".txt")));if(fc.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;if(!pdf)Files.writeString(fc.getSelectedFile().toPath(),text,StandardCharsets.UTF_8);else try(PDDocument doc=new PDDocument()){PDPage page=new PDPage();doc.addPage(page);try(PDPageContentStream cs=new PDPageContentStream(doc,page)){cs.beginText();cs.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER),12);cs.newLineAtOffset(55,740);cs.setLeading(19);for(String line:text.split("\n")){String ascii=Normalizer.normalize(line.replace("đ","d").replace("Đ","D"),Normalizer.Form.NFD).replaceAll("\\p{M}","").replaceAll("[^\\x20-\\x7E]","?");cs.showText(ascii);cs.newLine();}cs.endText();}doc.save(fc.getSelectedFile());}info(this,"Invoice exported: "+fc.getSelectedFile());}catch(Exception e){err(this,e);}}
        void myBookings(){page("Booking history","Bookings associated with your account.");try{content.add(tablePanel(new JTable(query("SELECT b.id 'ID',r.number 'Room',r.type 'Type',b.checkin 'Check-in',b.checkout 'Check-out',b.status 'Status',b.total 'Paid' FROM bookings b JOIN rooms r ON r.id=b.room_id WHERE b.guest_id="+user.id()+" ORDER BY b.id DESC"))),BorderLayout.CENTER);}catch(Exception e){err(this,e);}refresh();}
        void review(){page("Rate our service","Share your experience with the hotel.");JPanel form=new JPanel(new GridBagLayout());form.setBackground(Color.WHITE);form.setBorder(new EmptyBorder(20,20,20,20));GridBagConstraints c=new GridBagConstraints();c.gridx=0;c.gridy=0;c.anchor=GridBagConstraints.WEST;c.insets=new Insets(8,8,8,8);JComboBox<Integer> stars=new JComboBox<>(new Integer[]{5,4,3,2,1});JTextArea comment=new JTextArea(5,45);comment.setLineWrap(true);form.add(label("Rating"),c);c.gridx=1;form.add(stars,c);c.gridx=0;c.gridy++;form.add(label("Comment"),c);c.gridx=1;form.add(new JScrollPane(comment),c);c.gridx=1;c.gridy++;JButton send=button("Submit review",TEAL);send.addActionListener(e->{try(PreparedStatement p=db.prepareStatement("INSERT INTO reviews(guest_id,rating,comment) VALUES(?,?,?)")){p.setInt(1,user.id());p.setInt(2,(Integer)stars.getSelectedItem());p.setString(3,comment.getText());p.executeUpdate();info(this,"Thank you for your feedback!");}catch(Exception ex){err(this,ex);}});form.add(send,c);content.add(form,BorderLayout.NORTH);refresh();}
        void staff(){page("Staff management","Create administrator, receptionist, and customer accounts.");try{JTable t=new JTable(query("SELECT id 'ID',name 'Full name',username 'Username',role 'Role',phone 'Phone' FROM users ORDER BY role,name"));JButton add=button("＋ Create account",TEAL);add.addActionListener(e->staffDialog());JPanel holder=new JPanel(new BorderLayout());holder.setOpaque(false);holder.add(add,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);}catch(Exception e){err(this,e);}refresh();}
        void staffDialog(){JTextField name=new JTextField(),username=new JTextField(),phone=new JTextField();JPasswordField pass=new JPasswordField();JComboBox<String> role=new JComboBox<>(new String[]{"ADMIN","RECEPTIONIST","CUSTOMER"});Object[] f={"Full name",name,"Username",username,"Password",pass,"Role",role,"Phone",phone};if(JOptionPane.showConfirmDialog(this,f,"Create account",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try{String u=username.getText().trim();if(u.isEmpty()){info(this,"Please enter a username.");return;}try(PreparedStatement q=db.prepareStatement("SELECT COUNT(*) FROM users WHERE LOWER(username)=LOWER(?)")){q.setString(1,u);try(ResultSet r=q.executeQuery()){if(r.next()&&r.getInt(1)>0){info(this,"Username already exists. Please choose another username.");return;}}}try(PreparedStatement p=db.prepareStatement("INSERT INTO users(name,username,password,role,phone) VALUES(?,?,?,?,?)")){p.setString(1,name.getText().trim());p.setString(2,u);p.setString(3,encodePassword(new String(pass.getPassword())));p.setString(4,(String)role.getSelectedItem());p.setString(5,phone.getText());p.executeUpdate();staff();}}catch(Exception e){err(this,e);}}
        void analytics(){page("Revenue & reports","Revenue from checked-out bookings, grouped by day, month, or year.");JComboBox<String> period=new JComboBox<>(new String[]{"Day","Month","Year"});JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);top.add(label("Group by"));top.add(period);JPanel graph=new JPanel(new BorderLayout());graph.setBackground(Color.WHITE);graph.setBorder(new EmptyBorder(14,14,14,14));JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(graph,BorderLayout.CENTER);content.add(holder,BorderLayout.CENTER);Runnable draw=()->{try{String fmt=period.getSelectedIndex()==0?"%Y-%m-%d":period.getSelectedIndex()==1?"%Y-%m":"%Y";JTable data=new JTable(query("SELECT strftime('"+fmt+"',actual_out) 'Period',SUM(total) 'Revenue' FROM bookings WHERE status='Checked out' GROUP BY 1 ORDER BY 1 DESC LIMIT 12"));graph.removeAll();graph.add(new JScrollPane(data),BorderLayout.NORTH);graph.add(new RevenueChart(data),BorderLayout.CENTER);graph.revalidate();}catch(Exception e){err(this,e);}};period.addActionListener(e->draw.run());draw.run();refresh();}
        void cleaning(){
            page("Cleaning","Rooms that need cleaning after check-out. Mark a room as cleaned to make it available again.");
            try{
                JTable t=new JTable(query("SELECT id 'ID',number 'Room number',type 'Type',price 'Rate / night',status 'Status' FROM rooms WHERE status='Cleaning' ORDER BY number"));
                JButton done=button("✓ Mark as cleaned → Available",TEAL);
                done.addActionListener(e->{int id=selected(t);if(id<0)return;try(PreparedStatement p=db.prepareStatement("UPDATE rooms SET status='Available' WHERE id=? AND status='Cleaning'")){p.setInt(1,id);if(p.executeUpdate()>0){info(this,"Room is now Available.");cleaning();}else info(this,"This room is not waiting for cleaning.");}catch(Exception ex){err(this,ex);}});
                JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.setOpaque(false);top.add(done);
                JPanel holder=new JPanel(new BorderLayout(0,12));holder.setOpaque(false);holder.add(top,BorderLayout.NORTH);holder.add(tablePanel(t),BorderLayout.CENTER);
                content.add(holder,BorderLayout.CENTER);
            }catch(Exception e){err(this,e);}
            refresh();
        }
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

package NamanDigital;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

    ///Inventory Management System

    public class InventoryManagement {

        // ====== MODELS ======
        static class Item {
            long id;
            String name;
            String category;
            int quantity; // current stock
            double unitPrice;

            public Item(long id, String name, String category, int quantity, double unitPrice) {
                this.id = id;
                this.name = name;
                this.category = category;
                this.quantity = quantity;
                this.unitPrice = unitPrice;
            }
        }

        enum TxType { IN, OUT }

        static class Txn {
            long id;
            long itemId;
            TxType type;
            int quantity;
            LocalDateTime timestamp;
            String note;

            public Txn(long id, long itemId, TxType type, int quantity, LocalDateTime timestamp, String note) {
                this.id = id;
                this.itemId = itemId;
                this.type = type;
                this.quantity = quantity;
                this.timestamp = timestamp;
                this.note = note;
            }
        }

        static class User {
            String username;
            String passwordHash; // SHA-256 hex

            User(String username, String passwordHash) {
                this.username = username;
                this.passwordHash = passwordHash;
            }
        }

        // ====== SIMPLE CSV STORAGE ======
        static class CsvStore {
            final Path baseDir;
            final Path itemsCsv;
            final Path txnsCsv;
            final Path usersCsv;
            final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            CsvStore(String dir) {
                this.baseDir = Paths.get(dir);
                this.itemsCsv = baseDir.resolve("items.csv");
                this.txnsCsv = baseDir.resolve("transactions.csv");
                this.usersCsv = baseDir.resolve("users.csv");
            }

            void initIfNeeded() {
                try {
                    if (!Files.exists(baseDir)) Files.createDirectories(baseDir);
                    if (!Files.exists(itemsCsv)) {
                        Files.write(itemsCsv, Collections.singletonList("id,name,category,quantity,unitPrice"), StandardCharsets.UTF_8);
                    }
                    if (!Files.exists(txnsCsv)) {
                        Files.write(txnsCsv, Collections.singletonList("id,itemId,type,quantity,timestamp,note"), StandardCharsets.UTF_8);
                    }
                    if (!Files.exists(usersCsv)) {
                        Files.write(usersCsv, Collections.singletonList("username,passwordHash"), StandardCharsets.UTF_8);
                        // create default admin/admin123
                        String hash = SecurityUtil.sha256Hex("admin123");
                        Files.write(usersCsv, Collections.singletonList("admin," + hash), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to initialize storage: " + e.getMessage(), e);
                }
            }

            // --- Items ---
            List<Item> loadItems() {
                List<Item> list = new ArrayList<>();
                try (BufferedReader br = Files.newBufferedReader(itemsCsv, StandardCharsets.UTF_8)) {
                    String line; boolean first = true;
                    while ((line = br.readLine()) != null) {
                        if (first) { first = false; continue; }
                        if (line.trim().isEmpty()) continue;
                        String[] p = splitCsv(line);
                        long id = Long.parseLong(p[0]);
                        String name = p[1];
                        String cat = p[2];
                        int qty = Integer.parseInt(p[3]);
                        double price = Double.parseDouble(p[4]);
                        list.add(new Item(id, name, cat, qty, price));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return list;
            }

            void saveItems(List<Item> items) {
                List<String> lines = new ArrayList<>();
                lines.add("id,name,category,quantity,unitPrice");
                for (Item it : items) {
                    lines.add(String.join(",",
                            String.valueOf(it.id),
                            esc(it.name),
                            esc(it.category),
                            String.valueOf(it.quantity),
                            String.valueOf(it.unitPrice)));
                }
                try { Files.write(itemsCsv, lines, StandardCharsets.UTF_8); }
                catch (IOException e) { throw new RuntimeException(e); }
            }

            // --- Transactions ---
            List<Txn> loadTxns() {
                List<Txn> list = new ArrayList<>();
                try (BufferedReader br = Files.newBufferedReader(txnsCsv, StandardCharsets.UTF_8)) {
                    String line; boolean first = true;
                    while ((line = br.readLine()) != null) {
                        if (first) { first = false; continue; }
                        if (line.trim().isEmpty()) continue;
                        String[] p = splitCsv(line);
                        long id = Long.parseLong(p[0]);
                        long itemId = Long.parseLong(p[1]);
                        TxType t = TxType.valueOf(p[2]);
                        int qty = Integer.parseInt(p[3]);
                        LocalDateTime ts = LocalDateTime.parse(p[4], TS);
                        String note = p.length > 5 ? unesc(p[5]) : "";
                        list.add(new Txn(id, itemId, t, qty, ts, note));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return list;
            }

            void appendTxn(Txn tx) {
                String line = String.join(",",
                        String.valueOf(tx.id),
                        String.valueOf(tx.itemId),
                        tx.type.name(),
                        String.valueOf(tx.quantity),
                        tx.timestamp.format(TS),
                        esc(tx.note));
                try {
                    Files.write(txnsCsv, Collections.singletonList(line), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                } catch (IOException e) { throw new RuntimeException(e); }
            }

            // --- Users ---
            List<User> loadUsers() {
                List<User> list = new ArrayList<>();
                try (BufferedReader br = Files.newBufferedReader(usersCsv, StandardCharsets.UTF_8)) {
                    String line; boolean first = true;
                    while ((line = br.readLine()) != null) {
                        if (first) { first = false; continue; }
                        if (line.trim().isEmpty()) continue;
                        String[] p = splitCsv(line);
                        list.add(new User(p[0], p[1]));
                    }
                } catch (IOException e) { throw new RuntimeException(e); }
                return list;
            }

            void saveUsers(List<User> users) {
                List<String> lines = new ArrayList<>();
                lines.add("username,passwordHash");
                for (User u : users) lines.add(u.username + "," + u.passwordHash);
                try { Files.write(usersCsv, lines, StandardCharsets.UTF_8); }
                catch (IOException e) { throw new RuntimeException(e); }
            }

            // --- CSV Helpers ---
            private static String esc(String s) {
                if (s == null) return "";
                boolean needQuotes = s.contains(",") || s.contains("\"") || s.contains("\n");
                String t = s.replace("\"", "\"\"");
                return needQuotes ? "\"" + t + "\"" : t;
            }
            private static String unesc(String s) {
                if (s.startsWith("\"") && s.endsWith("\"")) {
                    String inner = s.substring(1, s.length()-1);
                    return inner.replace("\"\"", "\"");
                }
                return s;
            }
            private static String[] splitCsv(String line) {
                List<String> parts = new ArrayList<>();
                StringBuilder cur = new StringBuilder();
                boolean inQuotes = false;
                for (int i=0;i<line.length();i++) {
                    char c=line.charAt(i);
                    if (inQuotes) {
                        if (c=='"') {
                            if (i+1<line.length() && line.charAt(i+1)=='"') { cur.append('"'); i++; }
                            else { inQuotes=false; }
                        } else cur.append(c);
                    } else {
                        if (c=='"') inQuotes=true;
                        else if (c==',') { parts.add(cur.toString()); cur.setLength(0); }
                        else cur.append(c);
                    }
                }
                parts.add(cur.toString());
                return parts.toArray(new String[0]);
            }
        }

        // ====== SECURITY ======
        static class SecurityUtil {
            static String sha256Hex(String input) {
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    for (byte b : hash) sb.append(String.format("%02x", b));
                    return sb.toString();
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        // ====== SERVICES ======
        static class UserService {
            private final CsvStore store;
            private List<User> users;

            UserService(CsvStore store) { this.store = store; this.users = store.loadUsers(); }

            boolean authenticate(String username, String password) {
                String hash = SecurityUtil.sha256Hex(password);
                for (User u : users) if (u.username.equals(username) && u.passwordHash.equals(hash)) return true;
                return false;
            }

            boolean changePassword(String username, String newPassword) {
                for (User u : users) if (u.username.equals(username)) { u.passwordHash = SecurityUtil.sha256Hex(newPassword); store.saveUsers(users); return true; }
                return false;
            }

            boolean addUser(String username, String password) {
                for (User u : users) if (u.username.equals(username)) return false;
                users.add(new User(username, SecurityUtil.sha256Hex(password)));
                store.saveUsers(users);
                return true;
            }

            boolean deleteUser(String username) {
                boolean removed = users.removeIf(u -> u.username.equals(username));
                if (removed) store.saveUsers(users);
                return removed;
            }
        }

        static class InventoryService {
            private final CsvStore store;
            private List<Item> items;

            InventoryService(CsvStore store) { this.store = store; this.items = store.loadItems(); }

            List<Item> listItems() { return new ArrayList<>(items); }

            Item findById(long id) { return items.stream().filter(i->i.id==id).findFirst().orElse(null); }

            Item createItem(String name, String category, int quantity, double unitPrice) {
                long id = nextItemId();
                Item it = new Item(id, name, category, quantity, unitPrice);
                items.add(it);
                store.saveItems(items);
                return it;
            }

            boolean updateItem(long id, String name, String category, Integer quantity, Double unitPrice) {
                Item it = findById(id);
                if (it==null) return false;
                if (name!=null) it.name = name;
                if (category!=null) it.category = category;
                if (quantity!=null) it.quantity = quantity;
                if (unitPrice!=null) it.unitPrice = unitPrice;
                store.saveItems(items);
                return true;
            }

            boolean deleteItem(long id) {
                boolean ok = items.removeIf(i->i.id==id);
                if (ok) store.saveItems(items);
                return ok;
            }

            void adjustStock(long itemId, int delta) {
                Item it = findById(itemId);
                if (it==null) throw new IllegalArgumentException("Item not found: " + itemId);
                int newQty = it.quantity + delta;
                if (newQty < 0) throw new IllegalArgumentException("Insufficient stock. Current: " + it.quantity);
                it.quantity = newQty;
                store.saveItems(items);
            }

            private long nextItemId() {
                return items.stream().map(i->i.id).max(Long::compare).orElse(1000L) + 1;
            }
        }

        static class TransactionService {
            private final CsvStore store;
            private final InventoryService inventory;
            private List<Txn> txns;

            TransactionService(CsvStore store, InventoryService inventory) {
                this.store = store; this.inventory = inventory; this.txns = store.loadTxns();
            }

            List<Txn> listAll() { return new ArrayList<>(txns); }

            List<Txn> listByDate(LocalDate from, LocalDate to) {
                LocalDateTime start = from.atStartOfDay();
                LocalDateTime end = to.plusDays(1).atStartOfDay();
                List<Txn> out = new ArrayList<>();
                for (Txn t : txns) if (!t.timestamp.isBefore(start) && t.timestamp.isBefore(end)) out.add(t);
                return out;
            }

            Txn record(long itemId, TxType type, int quantity, String note) {
                if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
                int delta = type==TxType.IN ? quantity : -quantity;
                inventory.adjustStock(itemId, delta);
                long id = nextTxnId();
                Txn t = new Txn(id, itemId, type, quantity, LocalDateTime.now(), note==null?"":note);
                txns.add(t);
                store.appendTxn(t);
                return t;
            }

            private long nextTxnId() { return txns.stream().map(x->x.id).max(Long::compare).orElse(5000L) + 1; }
        }

        static class ReportService {
            private final InventoryService inventory;
            private final TransactionService txnService;

            ReportService(InventoryService inv, TransactionService tx) { this.inventory = inv; this.txnService = tx; }

            Path generateStockReportHtml(String outDir) {
                List<Item> items = inventory.listItems();
                items.sort(Comparator.comparingInt(i->i.quantity)); // low to high
                StringBuilder html = new StringBuilder();
                html.append("<html><head><meta charset='utf-8'><title>Stock Report</title>");
                html.append("<style>body{font-family:Arial;margin:24px} table{border-collapse:collapse;width:100%} th,td{border:1px solid #999;padding:8px;text-align:left} th{background:#eee} .right{text-align:right}</style>");
                html.append("</head><body><h2>Stock Levels</h2>");
                html.append("<p>Generated: ").append(LocalDateTime.now()).append("</p>");
                html.append("<table><tr><th>ID</th><th>Name</th><th>Category</th><th class='right'>Qty</th><th class='right'>Unit Price</th><th class='right'>Value</th></tr>");
                double total = 0.0;
                for (Item it : items) {
                    double value = it.quantity * it.unitPrice;
                    total += value;
                    html.append("<tr><td>").append(it.id).append("</td><td>")
                            .append(escape(it.name)).append("</td><td>")
                            .append(escape(it.category)).append("</td><td class='right'>")
                            .append(it.quantity).append("</td><td class='right'>")
                            .append(String.format(Locale.US, "%.2f", it.unitPrice)).append("</td><td class='right'>")
                            .append(String.format(Locale.US, "%.2f", value)).append("</td></tr>");
                }
                html.append("<tr><th colspan='5' class='right'>Total Inventory Value</th><th class='right'>")
                        .append(String.format(Locale.US, "%.2f", total)).append("</th></tr>");
                html.append("</table></body></html>");
                try {
                    Path out = Paths.get(outDir).resolve("stock_report_" + System.currentTimeMillis() + ".html");
                    Files.createDirectories(out.getParent());
                    Files.write(out, html.toString().getBytes(StandardCharsets.UTF_8));
                    return out;
                } catch (IOException e) { throw new RuntimeException(e); }
            }

            Path generateTransactionsReportHtml(String outDir, LocalDate from, LocalDate to) {
                List<Txn> list = txnService.listByDate(from, to);
                Map<Long, Item> itemMap = new HashMap<>();
                for (Item it : inventory.listItems()) itemMap.put(it.id, it);

                StringBuilder html = new StringBuilder();
                html.append("<html><head><meta charset='utf-8'><title>Transactions Report</title>");
                html.append("<style>body{font-family:Arial;margin:24px} table{border-collapse:collapse;width:100%} th,td{border:1px solid #999;padding:8px;text-align:left} th{background:#eee} .right{text-align:right}</style>");
                html.append("</head><body><h2>Transactions ");
                html.append("(" + from + " to " + to + ")</h2>");
                html.append("<p>Generated: ").append(LocalDateTime.now()).append("</p>");
                html.append("<table><tr><th>ID</th><th>Time</th><th>Item</th><th>Type</th><th class='right'>Qty</th><th>Note</th></tr>");
                for (Txn t : list) {
                    Item it = itemMap.get(t.itemId);
                    html.append("<tr><td>").append(t.id).append("</td><td>")
                            .append(t.timestamp).append("</td><td>")
                            .append(it==null? ("#"+t.itemId) : escape(it.name)).append("</td><td>")
                            .append(t.type).append("</td><td class='right'>")
                            .append(t.quantity).append("</td><td>")
                            .append(escape(t.note)).append("</td></tr>");
                }
                html.append("</table></body></html>");
                try {
                    Path out = Paths.get(outDir).resolve("transactions_" + from + "to" + to + "_" + System.currentTimeMillis() + ".html");
                    Files.createDirectories(out.getParent());
                    Files.write(out, html.toString().getBytes(StandardCharsets.UTF_8));
                    return out;
                } catch (IOException e) { throw new RuntimeException(e); }
            }

            private String escape(String s) {
                return s==null?"":s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
            }
        }

        // ====== CONSOLE UI ======
        static class ConsoleApp {
            private final Scanner sc = new Scanner(System.in);
            private final UserService users;
            private final InventoryService inv;
            private final TransactionService tx;
            private final ReportService reports;
            private String currentUser;

            ConsoleApp(UserService users, InventoryService inv, TransactionService tx, ReportService reports) {
                this.users = users; this.inv = inv; this.tx = tx; this.reports = reports;
            }

            void run() {
                if (!login()) return;
                while (true) {
                    System.out.println("\n Welcome to Inventory System  (logged in as: " + currentUser + ")");
                    System.out.println("1) Items (CRUD)");
                    System.out.println("2) Transactions (IN/OUT)");
                    System.out.println("3) Reports");
                    System.out.println("4) Users");
                    System.out.println("0) Exit");
                    System.out.print("Choose: ");
                    String choice = sc.nextLine().trim();
                    switch (choice) {
                        case "1": itemsMenu(); break;
                        case "2": txMenu(); break;
                        case "3": reportsMenu(); break;
                        case "4": usersMenu(); break;
                        case "0": System.out.println("Goodbye!"); return;
                        default: System.out.println("Invalid option.");
                    }
                }
            }

            private boolean login() {
                int attempts = 0;
                while (attempts < 3) {
                    System.out.print("Username: ");
                    String u = sc.nextLine().trim();
                    System.out.print("Password: ");
                    String p = sc.nextLine().trim();
                    if (users.authenticate(u, p)) { currentUser = u; return true; }
                    System.out.println("Invalid credentials. Try again.");
                    attempts++;
                }
                System.out.println("Too many attempts. Exiting.");
                return false;
            }

            private void itemsMenu() {
                while (true) {
                    System.out.println("\n-- Items --");
                    System.out.println("1) List");
                    System.out.println("2) Create");
                    System.out.println("3) Update");
                    System.out.println("4) Delete");
                    System.out.println("0) Back");
                    System.out.print("Choose: ");
                    String c = sc.nextLine().trim();
                    switch (c) {
                        case "1": listItems(); break;
                        case "2": createItem(); break;
                        case "3": updateItem(); break;
                        case "4": deleteItem(); break;
                        case "0": return;
                        default: System.out.println("Invalid option.");
                    }
                }
            }

            private void listItems() {
                System.out.println("ID | Name | Category | Qty | UnitPrice");
                for (Item it : inv.listItems()) {
                    System.out.printf(Locale.US, "%d | %s | %s | %d | %.2f%n", it.id, it.name, it.category, it.quantity, it.unitPrice);
                }
            }

            private void createItem() {
                try {
                    System.out.print("Name: "); String name = sc.nextLine();
                    System.out.print("Category: "); String cat = sc.nextLine();
                    System.out.print("Quantity: "); int qty = Integer.parseInt(sc.nextLine());
                    System.out.print("Unit Price: "); double price = Double.parseDouble(sc.nextLine());
                    Item it = inv.createItem(name, cat, qty, price);
                    System.out.println("Created item with ID: " + it.id);
                } catch (Exception e) { System.out.println("Error: " + e.getMessage()); }
            }

            private void updateItem() {
                try {
                    System.out.print("Item ID: "); long id = Long.parseLong(sc.nextLine());
                    Item it = inv.findById(id);
                    if (it==null) { System.out.println("Not found"); return; }
                    System.out.print("New name (blank to keep '"+it.name+"'): "); String name = sc.nextLine();
                    System.out.print("New category (blank to keep '"+it.category+"'): "); String cat = sc.nextLine();
                    System.out.print("New quantity (blank to keep '"+it.quantity+"'): "); String qtyS = sc.nextLine();
                    System.out.print("New unit price (blank to keep '"+it.unitPrice+"'): "); String priceS = sc.nextLine();
                    inv.updateItem(id,
                            name.isBlank()?null:name,
                            cat.isBlank()?null:cat,
                            qtyS.isBlank()?null:Integer.parseInt(qtyS),
                            priceS.isBlank()?null:Double.parseDouble(priceS));
                    System.out.println("Updated.");
                } catch (Exception e) { System.out.println("Error: " + e.getMessage()); }
            }

            private void deleteItem() {
                try {
                    System.out.print("Item ID: "); long id = Long.parseLong(sc.nextLine());
                    boolean ok = inv.deleteItem(id);
                    System.out.println(ok?"Deleted.":"Not found.");
                } catch (Exception e) { System.out.println("Error: " + e.getMessage()); }
            }

            private void txMenu() {
                while (true) {
                    System.out.println("\n-- Transactions --");
                    System.out.println("1) Record IN (Purchase)");
                    System.out.println("2) Record OUT (Sale)");
                    System.out.println("3) List All");
                    System.out.println("0) Back");
                    System.out.print("Choose: ");
                    String c = sc.nextLine().trim();
                    switch (c) {
                        case "1": recordTx(TxType.IN); break;
                        case "2": recordTx(TxType.OUT); break;
                        case "3": listTx(); break;
                        case "0": return;
                        default: System.out.println("Invalid option.");
                    }
                }
            }

            private void recordTx(TxType type) {
                try {
                    System.out.print("Item ID: "); long id = Long.parseLong(sc.nextLine());
                    Item it = inv.findById(id);
                    if (it==null) { System.out.println("Not found"); return; }
                    System.out.print("Quantity: "); int qty = Integer.parseInt(sc.nextLine());
                    System.out.print("Note (optional): "); String note = sc.nextLine();
                    Txn t = tx.record(id, type, qty, note);
                    System.out.println("Recorded TXN #" + t.id + ". Current stock for '"+it.name+"' = " + inv.findById(id).quantity);
                } catch (Exception e) { System.out.println("Error: " + e.getMessage()); }
            }

            private void listTx() {
                System.out.println("ID | Time | ItemId | Type | Qty | Note");
                for (Txn t : tx.listAll()) {
                    System.out.printf("%d | %s | %d | %s | %d | %s%n", t.id, t.timestamp, t.itemId, t.type, t.quantity, t.note);
                }
            }

            private void reportsMenu() {
                while (true) {
                    System.out.println("\n-- Reports --");
                    System.out.println("1) Generate Stock Level Report (HTML)");
                    System.out.println("2) Generate Transactions Report by Date (HTML)");
                    System.out.println("0) Back");
                    System.out.print("Choose: ");
                    String c = sc.nextLine().trim();
                    switch (c) {
                        case "1":
                            Path p1 = reports.generateStockReportHtml("reports");
                            System.out.println("Saved: " + p1.toAbsolutePath());
                            System.out.println("Open in browser and press Ctrl+P to print.");
                            break;
                        case "2":
                            try {
                                System.out.print("From date (yyyy-MM-dd): "); LocalDate from = LocalDate.parse(sc.nextLine().trim());
                                System.out.print("To date   (yyyy-MM-dd): "); LocalDate to   = LocalDate.parse(sc.nextLine().trim());
                                Path p2 = reports.generateTransactionsReportHtml("reports", from, to);
                                System.out.println("Saved: " + p2.toAbsolutePath());
                                System.out.println("Open in browser and press Ctrl+P to print.");
                            } catch (Exception e) { System.out.println("Error: " + e.getMessage()); }
                            break;
                        case "0": return;
                        default: System.out.println("Invalid option.");
                    }
                }
            }

            private void usersMenu() {
                while (true) {
                    System.out.println("\n-- Users --");
                    System.out.println("1) Add User");
                    System.out.println("2) Delete User");
                    System.out.println("3) Change My Password");
                    System.out.println("0) Back");
                    System.out.print("Choose: ");
                    String c = sc.nextLine().trim();
                    switch (c) {
                        case "1":
                            System.out.print("New username: "); String u = sc.nextLine().trim();
                            System.out.print("Password: "); String p = sc.nextLine().trim();
                            boolean ok = users.addUser(u, p);
                            System.out.println(ok?"User added.":"Already exists.");
                            break;
                        case "2":
                            System.out.print("Username to delete: "); String del = sc.nextLine().trim();
                            if (del.equals(currentUser)) { System.out.println("Cannot delete the logged-in user."); break; }
                            boolean removed = users.deleteUser(del);
                            System.out.println(removed?"Deleted.":"Not found.");
                            break;
                        case "3":
                            System.out.print("New password: "); String np = sc.nextLine().trim();
                            boolean changed = users.changePassword(currentUser, np);
                            System.out.println(changed?"Password changed.":"Failed.");
                            break;
                        case "0": return;
                        default: System.out.println("Invalid option.");
                    }
                }
            }
        }

        // ====== MAIN ======
        public static void main(String[] args) {
            CsvStore store = new CsvStore("data");
            store.initIfNeeded();

            UserService userService = new UserService(store);
            InventoryService invService = new InventoryService(store);
            TransactionService txService = new TransactionService(store, invService);
            ReportService reportService = new ReportService(invService, txService);

            ConsoleApp app = new ConsoleApp(userService, invService, txService, reportService);
            app.run();
        }
    }


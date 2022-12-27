use sqlite::{Connection, State};

pub trait Storage {
    /// Saves new or updates old address for this ID, and returns TTL in seconds
    fn save_address(&self, id: &[u8], ip: &[u8], signature: &[u8], port: u16, priority: u8, client: u32) -> u64;
    /// Gets all saved addresses
    fn get_addresses(&self, id: &[u8]) -> Vec<Addr>;
}

const SQL_CREATE_TABLES: &str = include_str!("create_db.sql");
const SQL_INSERT_IP: &str = "INSERT INTO clients (id, ip, signature, port, priority, client, timestamp, ttl) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
const SQL_UPDATE_IP: &str = "UPDATE clients SET port=?, priority=?, timestamp=?, ttl=? WHERE id=? AND ip=? AND client=?";
const SQL_SELECT_IPS: &str = "SELECT ip, signature, port, priority, client, timestamp, ttl FROM clients WHERE id=?";

pub struct SqliteStorage {
    db: Connection
}

const DEFAULT_PORT: u16 = 5050;
const DEFAULT_TTL: u64 = 86400;

impl SqliteStorage {
    pub fn new(db_name: &str) -> Self {
        let db = sqlite::open(db_name).expect("Unable to open sqlite DB");
        db.execute(SQL_CREATE_TABLES).expect("Error creating DB tables");
        SqliteStorage { db }
    }

    fn is_address_saved(&self, id: &[u8], ip: &[u8]) -> bool {
        let mut statement = self.db.prepare("SELECT * FROM clients WHERE id = ? AND ip = ?").expect("Error in is_address_saved");
        statement.bind((1, id)).expect("Error in bind");
        statement.bind((2, ip)).expect("Error in bind");
        return match statement.next().expect("Error in DB") {
            State::Row => true,
            State::Done => false
        };
    }

    fn save_new_address(&self, id: &[u8], ip: &[u8], signature: &[u8], port: u16, priority: u8, client: u32) -> u64 {
        let mut statement = self.db.prepare(SQL_INSERT_IP).expect("Error in save_new_address");
        statement.bind((1, id)).expect("Error in bind");
        statement.bind((2, ip)).expect("Error in bind");
        statement.bind((3, signature)).expect("Error in bind");
        statement.bind((4, port as i64)).expect("Error in bind");
        statement.bind((5, priority as i64)).expect("Error in bind");
        statement.bind((6, client as i64)).expect("Error in bind");
        statement.bind((7, get_utc_time() as i64)).expect("Error in bind");
        statement.bind((7, DEFAULT_TTL as i64)).expect("Error in bind");
        if let State::Done = statement.next().expect("Error in DB") {
            //println!("Saved new address");
            return DEFAULT_TTL
        }
        return 300
    }

    fn update_address(&self, id: &[u8], ip: &[u8], port: u16, priority: u8, client: u32) -> u64 {
        let mut statement = self.db.prepare(SQL_UPDATE_IP).expect("Error in update_address");
        statement.bind((1, port as i64)).expect("Error in bind");
        statement.bind((2, priority as i64)).expect("Error in bind");
        statement.bind((3, get_utc_time() as i64)).expect("Error in bind");
        statement.bind((4, DEFAULT_TTL as i64)).expect("Error in bind");
        statement.bind((5, id)).expect("Error in bind");
        statement.bind((6, ip)).expect("Error in bind");
        statement.bind((7, client as i64)).expect("Error in bind");
        if let State::Done = statement.next().expect("Error in DB") {
            //println!("Updated address");
            return DEFAULT_TTL
        }
        return 300
    }

    fn select_addresses(&self, id: &[u8]) -> Vec<Addr> {
        let cur_time = get_utc_time();
        let mut result = Vec::new();
        let mut statement = self.db.prepare(SQL_SELECT_IPS).expect("Error in select_addresses");
        statement.bind((1, id)).expect("Error in bind");
        while statement.next().unwrap() == State::Row {
            let ip: Vec<u8> = statement.read(0).unwrap();
            let signature: Vec<u8> = statement.read(1).unwrap();
            let port: i64 = statement.read(2).unwrap_or(DEFAULT_PORT as i64);
            let priority: i64 = statement.read(3).unwrap_or(0);
            let client: i64 = statement.read(4).unwrap_or(0);
            let time: i64 = statement.read(5).unwrap_or(0i64);
            let ttl: i64 = statement.read(6).unwrap_or(DEFAULT_TTL as i64);
            let expire = time + ttl;
            //println!("time: {}, ttl: {}, expire: {}, cur_time: {}", time, ttl, expire, cur_time);
            //println!("Got something {:?}", &ip);
            if cur_time > (expire as u64) {
                continue;
            }
            result.push(Addr { ip, signature, port: port as u16, priority: priority as u8, client: client as u32, ttl: 30/*ttl as u64*/ })
        }
        result
    }
}

impl Storage for SqliteStorage {
    fn save_address(&self, id: &[u8], ip: &[u8], signature: &[u8], port: u16, priority: u8, client: u32) -> u64 {
        if !self.is_address_saved(id, ip) {
            return self.save_new_address(id, ip, signature, port, priority, client);
        }
        self.update_address(id, ip, port, priority, client)
    }

    fn get_addresses(&self, id: &[u8]) -> Vec<Addr> {
        self.select_addresses(id)
    }
}

pub struct Addr {
    pub ip: Vec<u8>,
    pub signature: Vec<u8>,
    pub port: u16,
    pub priority: u8,
    pub client: u32,
    pub ttl: u64
}

pub fn get_utc_time() -> u64 {
    let sys_time = std::time::SystemTime::now();
    let elapsed = sys_time.duration_since(std::time::UNIX_EPOCH).unwrap();
    elapsed.as_secs()
}
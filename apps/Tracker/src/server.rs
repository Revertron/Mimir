use std::io::{Cursor, Read, Write};
use std::net::{Ipv6Addr, SocketAddr, UdpSocket};
use std::{io, thread};
use std::thread::JoinHandle;
use byteorder::{BigEndian, ReadBytesExt, WriteBytesExt};
use crate::functions::check_signature;
use crate::storage::{SqliteStorage, Storage};

pub struct Server {
    listen_address: String,
}

impl Server {
    pub fn new(listen_address: &str) -> Self {
        Server { listen_address: listen_address.to_owned() }
    }

    pub fn start(&self) -> JoinHandle<()> {
        let addr = self.listen_address.clone();
        thread::spawn(move || {
            let socket = UdpSocket::bind(addr.clone()).expect(&format!("Unable to bind to {}", &addr));
            println!("Started on {}", &addr);
            let mut buf = [0u8; 1024];
            let mut response = [0u8; 1024];
            let storage= SqliteStorage::new("mimir.sqlite");

            loop {
                if let Ok((length, src)) = socket.recv_from(&mut buf) {
                    match Self::process_message(&storage, &buf[..length], &mut response, src) {
                        Ok(size) => {
                            if let Err(e) = socket.send_to(&response[..size], src) {
                                println!("Error sending response to {}: {}", src, e);
                            }
                        }
                        Err(e) => {
                            println!("Error processing message: {:?}", e);
                        }
                    }
                }
            }
        })
    }

    fn process_message(storage: &SqliteStorage, data: &[u8], response: &mut [u8], src: SocketAddr) -> Result<usize, io::Error> {
        let mut c = Cursor::new(data);
        let _version = c.read_u8()?;
        let nonce = c.read_u32::<BigEndian>()?;
        let command = c.read_u8()?;
        let mut id = [0u8; 32];
        c.read_exact(&mut id)?;
        let hex = to_hex(&id);
        println!("Got packet from/for {} on {}", &hex, &src.ip());
        match command {
            0 => {
                let port = c.read_u16::<BigEndian>()?;
                let priority = c.read_u8()?;
                let client = c.read_u32::<BigEndian>()?;
                let mut ip = [0u8; 16];
                c.read_exact(&mut ip)?;
                let mut signature = [0u8; 64];
                c.read_exact(&mut signature)?;
                if !check_signature(&id, &signature, &ip) {
                    let ip = Ipv6Addr::from(ip);
                    println!("Wrong signature from {} for {}", &ip, &hex);
                    return Err(io::Error::from(io::ErrorKind::Other))
                }
                let ttl = storage.save_address(&id, &ip, &signature, port, priority, client);
                let mut w = Cursor::new(response);
                w.write_u32::<BigEndian>(nonce)?;
                w.write_u8(command)?;
                w.write_u64::<BigEndian>(ttl)?;
                return Ok(w.position() as usize);
            }
            1 => {
                let results = storage.get_addresses(&id);
                let mut w = Cursor::new(response);
                w.write_u32::<BigEndian>(nonce)?;
                w.write_u8(command)?;
                w.write_u8(results.len() as u8)?;
                println!("Got {} ips for {:?}", results.len(), &hex);
                for addr in results.iter() {
                    w.write_all(addr.ip.as_slice())?;
                    w.write_all(addr.signature.as_slice())?;
                    w.write_u16::<BigEndian>(addr.port)?;
                    w.write_u8(addr.priority)?;
                    w.write_u32::<BigEndian>(addr.client)?;
                    w.write_u64::<BigEndian>(addr.ttl)?;
                }
                return Ok(w.position() as usize);
            }
            _ => {
                println!("Wrong command from {}", src.ip());
            }
        }
        Err(io::Error::from(io::ErrorKind::Other))
    }
}

/// Convert bytes array to HEX format
pub fn to_hex(buf: &[u8]) -> String {
    let mut result = String::new();
    for x in buf.iter() {
        result.push_str(&format!("{:01$X}", x, 2));
    }
    result
}
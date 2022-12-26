use std::env;
use std::process::exit;
use crate::server::Server;

mod server;
mod storage;
mod functions;

fn main() {
    println!("Mimir tracker {}", env!("CARGO_PKG_VERSION"));
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        println!("Usage: ./tracker [IPv6]:port");
        exit(0);
    }

    let server = Server::new(&args[1]);
    server
        .start()
        .join()
        .expect("Could not join main server thread!");
}

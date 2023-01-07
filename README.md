# Mimir
True P2P messenger on top of Yggdrasil Network
(Alpha version)


## What is it?

We are taught that we can't communicate without servers. Why? Because we have NATs everywhere. We can't connect to each other without getting involved in some port-forwarding, STUN/TURN servers configurations or something like that. And even if you do all of that chances are that you couldn't connect to your contact because you have wrong type of NAT.
Only torrents circumvent these problems, and just because of some nodes that have "white" IPs.

The IPv6 was going to change this, give us all thousands of free IPs that we could connect to each other and forget those pesky NATs. But for decades ISPs are not eager to use it for one reason or another. It can be just a complexity, or heavier to pose a censorship.

But there is a network, called Yggdrasil, that gives everyone a free IPv6 and doesn't need a bless from your ISP. We finally have this possibility to use true P2P. And moreover, this network has a good encryption to protect all data that flows from one IP to another.

So, the Mimir is the first true P2P messenger to use these unusual conditions. Therefore, here are the usual parts of decent messenger that Mimir just don't need:

1. Messages encryption (the network takes care of that)
2. Proxying servers (no need, the connections are straight P2P)

What Mimir has?

1. Authentication of clients to each other based on Elliptic Curves
2. Big plans for world domination :)

## How it works?
Not so bad, actually.

Mimir works right on device, and all contacts connect to each other without using servers in the middle. Yggdrasil provides P2P connectivity without usual restrictions of regular Internet.

Mimir consists of three parts:

1. Server part - a TCP socket, that listens for connections from your contacts.
2. Client part - with a short life, it connects to needed contact to deliver a message. It is reused if the conversation goes on, and is closed after 2 minutes of inactivity.
3. Resolver - it connects to a server that acts as torrent tracker - saves IPs and handles them if asked by your contacts.
It is very handy if you change Yggdrasil IP frequently.
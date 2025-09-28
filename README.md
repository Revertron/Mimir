# Mimir
True P2P messenger on top of Yggdrasil Network
(Alpha version)

## Get Mimir

Right now, development is moving so fast that I can't rely on slow publishing systems.
So, you'll get only an APK for now: [Download APK](https://update.mimir-app.net/apk)

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
2. Sending/receiving text messages
3. Sending/receiving images
4. Audio calls with an impeccable sound quality
5. Big plans for world domination :)

One of Mimir’s strong points is censorship circumvention: you can connect to any of hundreds of available nodes, host your own, or even build a private network. Freedom is literally in your hands.

## How it works?
Mimir works right on device, having Yggdrasil node inside the client. All contacts connect to each other without using servers in the middle. Yggdrasil provides P2P connectivity without usual restrictions of regular Internet.

On top of Yggdrasil it uses QUIC implementation that listens for connections from others and makes connections to them when needed. The established connection is held for indefinite time if you are online to speed up conversations.

In order to support multi-device interoperability when you have multiple clients for the same account, the Yggdrasil's node ID (or IP) is not used for identification. Every client generates new Yggdrasil keys, and therefore IPs, on the start. SO, these contidions dictate that we have to implement some sort of "resolve" system, that will tell others what "IP" your client has now. I've named it "tracker". There are 5 interconnecting and syncing trackers in various parts of the world. We can extend that number if there will be a demand.

## Future development

Not in particular order:

* File transfer: Big files, no limits because it's P2P.
* Group chats: What a messenger without your groups?
* Video calls: Your mum will see your face and smile!
* News channels: Appealing articles in rich format with pictures.
* Other platforms: Now I'm creating a proof of concept for Android, later desktop and etc.
* Etc.: You tell me - issues on GitHub are open.

## Donations

If you find my project useful, please donate using any of these methods:

- [Patreon](https://www.patreon.com/Revertron)
- [GitHub Sponsors](https://github.com/sponsors/Revertron)
- [Buy me a Ko-fi](https://ko-fi.com/revertron)
- [Bitcoin](bitcoin:bc1qmfkwyk8hre7e5xq272sjakph5fwpumk5xc59q3)
- [TRX/Tron/TRC20](tronlink://transfer?address=TD6tYF64mSwV14FQWKZkUNMvLonNkdHJXA&amount=25&token=USDT)

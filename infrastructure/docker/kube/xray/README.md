# Shadowrocket

## Local server

| Field | Value |
|---|---|
| Type | `SOCKS5` |
| Address | `127.0.0.1` |
| Port | `1080` |
| UDP Relay | on |
| Remarks | `kube` |

## Module

```
#!name=KubeLan
#!desc= Kube mapping

[Rule]
IP-CIDR,10.200.0.0/24,kube
```

# OrbStack network

```
Mac                      192.168.139.3
└─ OrbStack Linux VM     gateway 192.168.139.1
   ├─ docker             192.168.139.2
   │  ├─ bridge          192.168.215.1/24
   │  ├─ kube_default    192.168.97.1/24
   │  ├─ frr             192.168.139.2, BGP → routes
   │  ├─ xray            192.168.97.2
   │  └─ routes          default via 192.168.139.1
   │                     10.200.0.x via worker1..3
   ├─ control-plane1..3  192.168.139.x
   └─ worker1..3         192.168.139.x
```

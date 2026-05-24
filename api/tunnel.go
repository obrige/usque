package api

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"log"
	"net"
	"sync"
	"time"

	connectip "github.com/Diniboy1123/connect-ip-go"
	"github.com/obrige/usque-android/internal"
	"github.com/songgao/water"
	"golang.zx2c4.com/wireguard/tun"
)

// NetBuffer is a pool of byte slices with a fixed capacity.
type NetBuffer struct {
	capacity int
	buf      sync.Pool
}

func (n *NetBuffer) Get() []byte {
	return *(n.buf.Get().(*[]byte))
}

func (n *NetBuffer) Put(buf []byte) {
	if cap(buf) != n.capacity {
		return
	}
	n.buf.Put(&buf)
}

func NewNetBuffer(capacity int) *NetBuffer {
	if capacity <= 0 {
		panic("capacity must be greater than 0")
	}
	return &NetBuffer{
		capacity: capacity,
		buf: sync.Pool{
			New: func() interface{} {
				b := make([]byte, capacity)
				return &b
			},
		},
	}
}

// TunnelDevice abstracts a TUN device.
type TunnelDevice interface {
	ReadPacket(buf []byte) (int, error)
	WritePacket(pkt []byte) error
}

// NetstackAdapter wraps a tun.Device to satisfy TunnelDevice.
type NetstackAdapter struct {
	dev             tun.Device
	tunnelBufPool   sync.Pool
	tunnelSizesPool sync.Pool
}

func (n *NetstackAdapter) ReadPacket(buf []byte) (int, error) {
	packetBufsPtr := n.tunnelBufPool.Get().(*[][]byte)
	sizesPtr := n.tunnelSizesPool.Get().(*[]int)

	defer func() {
		(*packetBufsPtr)[0] = nil
		n.tunnelBufPool.Put(packetBufsPtr)
		n.tunnelSizesPool.Put(sizesPtr)
	}()

	(*packetBufsPtr)[0] = buf
	(*sizesPtr)[0] = 0

	_, err := n.dev.Read(*packetBufsPtr, *sizesPtr, 0)
	if err != nil {
		return 0, err
	}

	return (*sizesPtr)[0], nil
}

func (n *NetstackAdapter) WritePacket(pkt []byte) error {
	_, err := n.dev.Write([][]byte{pkt}, 0)
	return err
}

func NewNetstackAdapter(dev tun.Device) TunnelDevice {
	return &NetstackAdapter{
		dev: dev,
		tunnelBufPool: sync.Pool{
			New: func() interface{} {
				buf := make([][]byte, 1)
				return &buf
			},
		},
		tunnelSizesPool: sync.Pool{
			New: func() interface{} {
				sizes := make([]int, 1)
				return &sizes
			},
		},
	}
}

// WaterAdapter wraps a *water.Interface to satisfy TunnelDevice.
type WaterAdapter struct {
	iface *water.Interface
}

func (w *WaterAdapter) ReadPacket(buf []byte) (int, error) {
	n, err := w.iface.Read(buf)
	if err != nil {
		return 0, err
	}
	return n, nil
}

func (w *WaterAdapter) WritePacket(pkt []byte) error {
	_, err := w.iface.Write(pkt)
	return err
}

func NewWaterAdapter(iface *water.Interface) TunnelDevice {
	return &WaterAdapter{iface: iface}
}

// MaintainTunnel continuously connects to the MASQUE server.
func MaintainTunnel(ctx context.Context, tlsConfig *tls.Config, keepalivePeriod time.Duration, initialPacketSize uint16, endpoint *net.UDPAddr, device TunnelDevice, mtu int, reconnectDelay time.Duration) {
	packetBufferPool := NewNetBuffer(mtu)
	for {
		log.Printf("Establishing MASQUE connection to %s:%d", endpoint.IP, endpoint.Port)
		udpConn, tr, ipConn, rsp, err := ConnectTunnel(
			ctx,
			tlsConfig,
			internal.DefaultQuicConfig(keepalivePeriod, initialPacketSize),
			internal.ConnectURI,
			endpoint,
		)
		if err != nil {
			log.Printf("Failed to connect tunnel: %v", err)
			time.Sleep(reconnectDelay)
			continue
		}
		if rsp.StatusCode != 200 {
			log.Printf("Tunnel connection failed: %s", rsp.Status)
			ipConn.Close()
			if udpConn != nil {
				udpConn.Close()
			}
			if tr != nil {
				tr.Close()
			}
			time.Sleep(reconnectDelay)
			continue
		}

		log.Println("Connected to MASQUE server")
		errChan := make(chan error, 2)

		go func() {
			for {
				buf := packetBufferPool.Get()
				n, err := device.ReadPacket(buf)
				if err != nil {
					packetBufferPool.Put(buf)
					errChan <- fmt.Errorf("failed to read from TUN device: %v", err)
					return
				}
				icmp, err := ipConn.WritePacket(buf[:n])
				if err != nil {
					packetBufferPool.Put(buf)
					if errors.As(err, new(*connectip.CloseError)) {
						errChan <- fmt.Errorf("connection closed while writing to IP connection: %v", err)
						return
					}
					log.Printf("Error writing to IP connection: %v, continuing...", err)
					continue
				}
				packetBufferPool.Put(buf)

				if len(icmp) > 0 {
					if err := device.WritePacket(icmp); err != nil {
						if errors.As(err, new(*connectip.CloseError)) {
							errChan <- fmt.Errorf("connection closed while writing ICMP to TUN device: %v", err)
							return
						}
						log.Printf("Error writing ICMP to TUN device: %v, continuing...", err)
					}
				}
			}
		}()

		go func() {
			buf := packetBufferPool.Get()
			defer packetBufferPool.Put(buf)
			for {
				n, err := ipConn.ReadPacket(buf, true)
				if err != nil {
					if errors.As(err, new(*connectip.CloseError)) {
						errChan <- fmt.Errorf("connection closed while reading from IP connection: %v", err)
						return
					}
					log.Printf("Error reading from IP connection: %v, continuing...", err)
					continue
				}
				if err := device.WritePacket(buf[:n]); err != nil {
					errChan <- fmt.Errorf("failed to write to TUN device: %v", err)
					return
				}
			}
		}()

		err = <-errChan
		log.Printf("Tunnel connection lost: %v. Reconnecting...", err)
		ipConn.Close()
		if udpConn != nil {
			udpConn.Close()
		}
		if tr != nil {
			tr.Close()
		}
		time.Sleep(reconnectDelay)
	}
}

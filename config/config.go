package config

import (
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"os"
)

// Config represents the application configuration structure.
type Config struct {
	PrivateKey     string `json:"private_key"`
	EndpointV4     string `json:"endpoint_v4"`
	EndpointV6     string `json:"endpoint_v6"`
	EndpointPubKey string `json:"endpoint_pub_key"`
	License        string `json:"license"`
	ID             string `json:"id"`
	AccessToken    string `json:"access_token"`
	IPv4           string `json:"ipv4"`
	IPv6           string `json:"ipv6"`
}

// AppConfig holds the global application configuration.
var AppConfig Config

// ConfigLoaded indicates whether the configuration has been successfully loaded.
var ConfigLoaded bool

// LoadConfig loads the application configuration from a JSON file.
func LoadConfig(configPath string) error {
	file, err := os.Open(configPath)
	if err != nil {
		return fmt.Errorf("failed to open config file: %v", err)
	}
	defer file.Close()

	decoder := json.NewDecoder(file)
	if err := decoder.Decode(&AppConfig); err != nil {
		return fmt.Errorf("failed to decode config file: %v", err)
	}

	ConfigLoaded = true

	return nil
}

// SaveConfig writes the current application configuration to a prettified JSON file.
func (*Config) SaveConfig(configPath string) error {
	file, err := os.Create(configPath)
	if err != nil {
		return fmt.Errorf("failed to create config file: %v", err)
	}
	defer file.Close()

	encoder := json.NewEncoder(file)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(AppConfig); err != nil {
		return fmt.Errorf("failed to encode config file: %v", err)
	}

	return nil
}

// GetEcPrivateKey retrieves the ECDSA private key from the stored Base64-encoded string.
func (*Config) GetEcPrivateKey() (*ecdsa.PrivateKey, error) {
	privKeyB64, err := base64.StdEncoding.DecodeString(AppConfig.PrivateKey)
	if err != nil {
		return nil, fmt.Errorf("failed to decode private key: %v", err)
	}

	privKey, err := x509.ParseECPrivateKey(privKeyB64)
	if err != nil {
		return nil, fmt.Errorf("failed to parse private key: %v", err)
	}

	return privKey, nil
}

// GetEcEndpointPublicKey retrieves the ECDSA public key from the stored PEM-encoded string.
func (*Config) GetEcEndpointPublicKey() (*ecdsa.PublicKey, error) {
	endpointPubKeyB64, _ := pem.Decode([]byte(AppConfig.EndpointPubKey))
	if endpointPubKeyB64 == nil {
		return nil, fmt.Errorf("failed to decode endpoint public key")
	}

	pubKey, err := x509.ParsePKIXPublicKey(endpointPubKeyB64.Bytes)
	if err != nil {
		return nil, fmt.Errorf("failed to parse public key: %v", err)
	}

	ecPubKey, ok := pubKey.(*ecdsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("failed to assert public key as ECDSA")
	}

	return ecPubKey, nil
}

// ParsePrivateKey parses a DER-encoded ECDSA private key from raw bytes.
// This is used by the Android layer when a custom private key is provided.
func ParsePrivateKey(privKeyBytes []byte) (*ecdsa.PrivateKey, error) {
	privKey, err := x509.ParseECPrivateKey(privKeyBytes)
	if err != nil {
		return nil, fmt.Errorf("failed to parse private key: %v", err)
	}
	return privKey, nil
}

package main

import (
	"fmt"
	"os"

	"github.com/obrige/usque-android/cmd"
)

func main() {
	if err := cmd.Execute(); err != nil {
		fmt.Println("Error:", err)
		os.Exit(1)
	}
}

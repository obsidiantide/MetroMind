import "./globals.css";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "MetroMind",
  description: "Digital twin of a metro system",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}

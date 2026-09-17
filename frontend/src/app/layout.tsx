import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { ThemeProvider } from "@/components/theme-provider";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Toaster } from "@/components/ui/toast";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "UTC Chatbot - Hỗ trợ Tuyển sinh ĐH Giao thông Vận tải",
  description:
    "Trợ lý AI hỗ trợ tuyển sinh Trường Đại học Giao thông Vận tải (UTC). Tra cứu điểm chuẩn, ngành đào tạo, học phí, và phương thức xét tuyển.",
  keywords: [
    "UTC",
    "Đại học Giao thông Vận tải",
    "tuyển sinh",
    "chatbot",
    "điểm chuẩn",
    "xét tuyển",
  ],
  authors: [{ name: "UTC" }],
  openGraph: {
    title: "UTC Chatbot - Hỗ trợ Tuyển sinh",
    description:
      "Trợ lý AI hỗ trợ tuyển sinh Trường Đại học Giao thông Vận tải",
    type: "website",
  },
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="vi"
      suppressHydrationWarning
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="h-full overflow-hidden">
        <ThemeProvider
          attribute="class"
          defaultTheme="dark"
          enableSystem
          disableTransitionOnChange
        >
          <TooltipProvider delay={300}>
            {children}
          </TooltipProvider>
          <Toaster />
        </ThemeProvider>
      </body>
    </html>
  );
}

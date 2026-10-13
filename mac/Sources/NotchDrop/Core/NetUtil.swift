import Foundation
import SystemConfiguration

enum NetUtil {
    /// Usable IPv4 addresses of this Mac (Wi-Fi, Ethernet, phone hotspot), excluding VPN/AWDL/link-local.
    static func ipv4Addresses() -> [String] {
        var result: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        let skip = ["lo", "utun", "awdl", "llw", "bridge", "anpi", "gif", "stf", "ap"]
        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let p = ptr {
            defer { ptr = p.pointee.ifa_next }
            let flags = Int32(p.pointee.ifa_flags)
            guard let sa = p.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET),
                  flags & IFF_UP != 0, flags & IFF_RUNNING != 0, flags & IFF_LOOPBACK == 0 else { continue }
            let name = String(cString: p.pointee.ifa_name)
            if skip.contains(where: { name.hasPrefix($0) }) { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let ip = String(cString: host)
                if !ip.hasPrefix("169.254.") { result.append(ip) }
            }
        }
        return result
    }

    /// IPv4 default gateway. When the Mac is on the phone's hotspot, this is the phone.
    static func defaultGateway() -> String? {
        guard let store = SCDynamicStoreCreate(nil, "NotchDrop" as CFString, nil, nil),
              let dict = SCDynamicStoreCopyValue(store, "State:/Network/Global/IPv4" as CFString) as? [String: Any]
        else { return nil }
        return dict["Router"] as? String
    }
}

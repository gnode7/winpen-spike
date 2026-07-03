package cc.gnode.winpen.spike

import com.sun.jna.*
import com.sun.jna.platform.win32.WinNT.HANDLE
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// ===== Constants =====
private const val TAG = "[mDNS-Spike]"
private const val SERVICE_TYPE = "_winpen._tcp"
private const val DOMAIN = "local"
private const val INSTANCE_NAME = "WinPen-Spike"
private const val TCP_PORT = 19820
private const val DNS_QUERY_REQUEST_VERSION1 = 1
private const val ERROR_SUCCESS = 0
private const val DNS_REQUEST_PENDING = 9506

// ===== JNA Interface for dnsapi.dll =====
interface DnsApi : Library {
    companion object {
        val INSTANCE: DnsApi = Native.load("Dnsapi", DnsApi::class.java)
    }

    // PDNS_SERVICE_INSTANCE DnsServiceConstructInstance(
    //   PCWSTR pServiceName, PCWSTR pHostName,
    //   PIP4_ADDRESS pIp4, PIP6_ADDRESS pIp6,
    //   WORD wPort, WORD wPriority, WORD wWeight,
    //   DWORD dwPropertiesCount, PCWSTR *keys, PCWSTR *values)
    fun DnsServiceConstructInstance(
        pServiceName: WString,
        pHostName: WString,
        pIp4: Pointer?,
        pIp6: Pointer?,
        wPort: Short,
        wPriority: Short,
        wWeight: Short,
        dwPropertiesCount: Int,
        keys: PointerArray,
        values: PointerArray
    ): Pointer?

    fun DnsServiceFreeInstance(pInstance: Pointer?)

    // DWORD DnsServiceRegister(PDNS_SERVICE_REGISTER_REQUEST pRequest, PDNS_SERVICE_CANCEL pCancel)
    fun DnsServiceRegister(request: DnsServiceRegisterRequest, cancel: Pointer?): Int

    // DWORD DnsServiceDeRegister(PDNS_SERVICE_REGISTER_REQUEST pRequest, PDNS_SERVICE_CANCEL pCancel)
    fun DnsServiceDeRegister(request: DnsServiceRegisterRequest, cancel: Pointer?): Int

    // DWORD DnsServiceBrowse(PDNS_SERVICE_BROWSE_REQUEST pRequest, PDNS_SERVICE_CANCEL pCancel)
    fun DnsServiceBrowse(request: DnsServiceBrowseRequest, cancel: Pointer?): Int

    // DWORD DnsServiceResolve(PDNS_SERVICE_RESOLVE_REQUEST pRequest, PDNS_SERVICE_CANCEL pCancel)
    fun DnsServiceResolve(request: DnsServiceResolveRequest, cancel: Pointer?): Int

    // DWORD DnsServiceBrowseCancel(PDNS_SERVICE_CANCEL pCancelHandle)
    fun DnsServiceBrowseCancel(cancel: Pointer?): Int
}

// ===== Helper: fixed-size pointer array (PCWSTR*) =====
class PointerArray(size: Int) : Memory((size * 8).toLong()) {
    fun setPointer(index: Int, ptr: Pointer?) {
        setPointer((index * 8).toLong(), ptr)
    }
}

// ===== Structs (matching Windows SDK windns.h exactly) =====

// DNS_SERVICE_REGISTER_REQUEST (Win64: 48 bytes)
//   ULONG Version                      offset 0
//   ULONG InterfaceIndex               offset 4
//   PDNS_SERVICE_INSTANCE pServiceInstance  offset 8
//   PDNS_SERVICE_REGISTER_COMPLETE pRegisterCompletionCallback  offset 16
//   PVOID pQueryContext                offset 24
//   HANDLE hCredentials                offset 32
//   BOOL unicastEnabled                offset 40
//   (4 bytes padding)                  offset 44
class DnsServiceRegisterRequest : Structure(8) {
    @JvmField var Version: Int = 0
    @JvmField var InterfaceIndex: Int = 0
    @JvmField var pServiceInstance: Pointer? = null
    @JvmField var pRegisterCompletionCallback: Callback? = null
    @JvmField var pQueryContext: Pointer? = null
    @JvmField var hCredentials: HANDLE? = null
    @JvmField var unicastEnabled: Boolean = false  // BOOL = 4 bytes

    override fun getFieldOrder(): List<String> =
        listOf("Version", "InterfaceIndex", "pServiceInstance",
               "pRegisterCompletionCallback", "pQueryContext",
               "hCredentials", "unicastEnabled")
}

// DNS_SERVICE_BROWSE_REQUEST (Win64: 32 bytes)
//   ULONG Version                      offset 0
//   ULONG InterfaceIndex               offset 4
//   PCWSTR QueryName                   offset 8
//   union { pBrowseCallback / pBrowseCallbackV2 }  offset 16
//   PVOID pQueryContext                offset 24
class DnsServiceBrowseRequest : Structure(8) {
    @JvmField var Version: Int = 0
    @JvmField var InterfaceIndex: Int = 0
    @JvmField var QueryName: Pointer? = null
    @JvmField var pBrowseCallback: Callback? = null
    @JvmField var pQueryContext: Pointer? = null

    override fun getFieldOrder(): List<String> =
        listOf("Version", "InterfaceIndex", "QueryName",
               "pBrowseCallback", "pQueryContext")
}

// DNS_SERVICE_RESOLVE_REQUEST (Win64: 32 bytes)
//   ULONG Version                      offset 0
//   ULONG InterfaceIndex               offset 4
//   PWSTR QueryName                    offset 8
//   PDNS_SERVICE_RESOLVE_COMPLETE pResolveCompletionCallback  offset 16
//   PVOID pQueryContext                offset 24
class DnsServiceResolveRequest : Structure(8) {
    @JvmField var Version: Int = 0
    @JvmField var InterfaceIndex: Int = 0
    @JvmField var QueryName: Pointer? = null
    @JvmField var pResolveCompletionCallback: Callback? = null
    @JvmField var pQueryContext: Pointer? = null

    override fun getFieldOrder(): List<String> =
        listOf("Version", "InterfaceIndex", "QueryName",
               "pResolveCompletionCallback", "pQueryContext")
}

// DNS_SERVICE_INSTANCE (Win64: 72 bytes)
//   LPWSTR pszInstanceName             offset 0
//   LPWSTR pszHostName                 offset 8
//   IP4_ADDRESS *ip4Address            offset 16
//   IP6_ADDRESS *ip6Address            offset 24
//   WORD wPort                         offset 32
//   WORD wPriority                     offset 34
//   WORD wWeight                       offset 36
//   (2 bytes padding)                  offset 38
//   DWORD dwPropertyCount              offset 40
//   PWSTR *keys                        offset 48
//   PWSTR *values                      offset 56
//   DWORD dwInterfaceIndex             offset 64
//   (4 bytes padding)                  offset 68
class DnsServiceInstance : Structure(8) {
    @JvmField var pszInstanceName: Pointer? = null
    @JvmField var pszHostName: Pointer? = null
    @JvmField var ip4Address: Pointer? = null
    @JvmField var ip6Address: Pointer? = null
    @JvmField var wPort: Short = 0
    @JvmField var wPriority: Short = 0
    @JvmField var wWeight: Short = 0
    @JvmField var dwPropertyCount: Int = 0
    @JvmField var keys: Pointer? = null
    @JvmField var values: Pointer? = null
    @JvmField var dwInterfaceIndex: Int = 0

    override fun getFieldOrder(): List<String> =
        listOf("pszInstanceName", "pszHostName", "ip4Address", "ip6Address",
               "wPort", "wPriority", "wWeight", "dwPropertyCount",
               "keys", "values", "dwInterfaceIndex")

    companion object {
        fun fromPointer(p: Pointer): DnsServiceInstance {
            val inst = DnsServiceInstance()
            inst.useMemory(p)
            inst.read()
            return inst
        }
    }
}

// ===== Callbacks =====

// DNS_SERVICE_REGISTER_COMPLETE: void(DWORD Status, PVOID pQueryContext, PDNS_SERVICE_INSTANCE pServiceInstance)
interface RegisterCallback : Callback {
    fun invoke(status: Int, pQueryContext: Pointer?, pServiceInstance: Pointer?)
}

// DNS_SERVICE_BROWSE_CALLBACK: void(DWORD Status, PVOID pQueryContext, PDNS_RECORD pDnsRecord)
interface BrowseCallback : Callback {
    fun invoke(status: Int, pQueryContext: Pointer?, pDnsRecord: Pointer?)
}

// DNS_SERVICE_RESOLVE_COMPLETE: void(DWORD Status, PVOID pQueryContext, PDNS_SERVICE_INSTANCE pServiceInstance)
interface ResolveCallback : Callback {
    fun invoke(status: Int, pQueryContext: Pointer?, pServiceInstance: Pointer?)
}

// ===== Keep references alive (prevent GC of callbacks) =====
private val keepAlive = mutableListOf<Any>()

private fun allocWStr(s: String): Memory {
    val buf = Memory(((s.length + 1) * 2).toLong())
    buf.setWideString(0, s)
    return buf
}

// ===== Main =====
fun main() {
    println("$TAG === WinPen mDNS Spike v3 ===")
    println()

    val computerName = System.getenv("COMPUTERNAME") ?: "UNKNOWN"
    val hostName = "$computerName.local"
    val instanceFullName = "$INSTANCE_NAME.$SERVICE_TYPE.$DOMAIN"

    println("$TAG Computer name:    $computerName")
    println("$TAG Host name:        $hostName")
    println("$TAG Service instance: $instanceFullName")
    println("$TAG TCP port:         $TCP_PORT")
    println()

    // -------- 1. Register --------
    println("$TAG --- Registration ---")
    println("$TAG [OBSERVE] Watch for Windows Firewall popup!")
    println("$TAG [OBSERVE] If popup appears, note which port it mentions (5353 vs 19820)")
    println()

    // TXT properties
    val txtKeys = listOf("ver", "name", "os")
    val txtValues = listOf("1", computerName, "win")

    // Build PCWSTR* arrays for keys and values
    val keysArray = PointerArray(txtKeys.size)
    val valuesArray = PointerArray(txtValues.size)
    txtKeys.forEachIndexed { i, k ->
        val mem = allocWStr(k)
        keepAlive.add(mem)
        keysArray.setPointer(i, mem)
    }
    txtValues.forEachIndexed { i, v ->
        val mem = allocWStr(v)
        keepAlive.add(mem)
        valuesArray.setPointer(i, mem)
    }
    keepAlive.add(keysArray)
    keepAlive.add(valuesArray)

    // DnsServiceConstructInstance builds a DNS_SERVICE_INSTANCE for us
    val pInstance = DnsApi.INSTANCE.DnsServiceConstructInstance(
        WString(instanceFullName),
        WString(hostName),
        null,  // pIp4 - let mDNS resolve from hostname
        null,  // pIp6
        TCP_PORT.toShort(),
        0.toShort(),  // priority
        0.toShort(),  // weight
        txtKeys.size,
        keysArray,
        valuesArray
    )

    if (pInstance == null) {
        println("$TAG DnsServiceConstructInstance returned null!")
        println("$TAG Press Enter to exit...")
        readlnOrNull()
        return
    }
    println("$TAG DnsServiceConstructInstance OK: $pInstance")

    // Register callback
    val regLatch = CountDownLatch(1)
    var regStatus: Int = -1
    val regCallback = object : RegisterCallback {
        override fun invoke(status: Int, pQueryContext: Pointer?, pServiceInstance: Pointer?) {
            println("$TAG Register callback fired: status=$status (0=success)")
            regStatus = status
            if (pServiceInstance != null) {
                try {
                    val inst = DnsServiceInstance.fromPointer(pServiceInstance)
                    println("$TAG   Instance: ${inst.pszInstanceName?.getWideString(0)}")
                    println("$TAG   Host:     ${inst.pszHostName?.getWideString(0)}")
                    println("$TAG   Port:     ${inst.wPort.toInt() and 0xFFFF}")
                } catch (e: Exception) {
                    println("$TAG   (parse error: ${e.message})")
                }
            }
            regLatch.countDown()
        }
    }
    keepAlive.add(regCallback)

    val regRequest = DnsServiceRegisterRequest().apply {
        Version = DNS_QUERY_REQUEST_VERSION1
        InterfaceIndex = 0  // all interfaces
        pServiceInstance = pInstance
        pRegisterCompletionCallback = regCallback
        pQueryContext = null
        hCredentials = null
        unicastEnabled = false  // use mDNS, not unicast DNS
    }
    regRequest.write()

    println("$TAG Calling DnsServiceRegister...")
    val regResult = DnsApi.INSTANCE.DnsServiceRegister(regRequest, null)
    println("$TAG DnsServiceRegister returned: $regResult (9506=PENDING=success)")

    if (regResult == DNS_REQUEST_PENDING) {
        println("$TAG Waiting for register callback (5s timeout)...")
        regLatch.await(5, TimeUnit.SECONDS)
        println("$TAG Register status: $regStatus")
    } else {
        println("$TAG Register failed immediately. GetLastError may have info.")
    }

    println()

    // -------- 2. Browse --------
    println("$TAG --- Browse ---")
    println("$TAG Browsing for $SERVICE_TYPE.$DOMAIN ...")

    val browseLatch = CountDownLatch(1)
    var browseStatus: Int = -1
    val browseCallback = object : BrowseCallback {
        override fun invoke(status: Int, pQueryContext: Pointer?, pDnsRecord: Pointer?) {
            println("$TAG Browse callback fired: status=$status (0=success)")
            browseStatus = status
            if (pDnsRecord != null) {
                // pDnsRecord points to a DNS_RECORDW chain
                // Parse PTR records: pName -> pName, wType=12 (PTR), Data.pNameHost
                var current: Pointer? = pDnsRecord
                var count = 0
                while (current != null && count < 10) {
                    try {
                        // DNS_RECORDW on Win64:
                        //   offset 0:  PDNS_RECORD pNext (8)
                        //   offset 8:  PWSTR pName (8)
                        //   offset 16: WORD wType (2)
                        //   offset 18: WORD wDataLength (2)
                        //   offset 20: DWORD dwFlags (4)
                        //   offset 24: DWORD dwTtl (4)
                        //   offset 28: DWORD dwReserved (4)
                        //   offset 32: Data union (varies)
                        //     For PTR (type 12): Data.pNameHost = PWSTR (8 bytes at offset 32)
                        val pNext = current.getPointer(0)
                        val pName = current.getPointer(8)
                        val wType = current.getShort(16).toInt() and 0xFFFF
                        val nameStr = pName?.getWideString(0) ?: "?"
                        val typeStr = when (wType) {
                            12 -> "PTR"
                            33 -> "SRV"
                            16 -> "TXT"
                            1 -> "A"
                            28 -> "AAAA"
                            else -> "type=$wType"
                        }
                        print("$TAG   [$count] $typeStr: $nameStr")
                        if (wType == 12) { // PTR
                            val pTarget = current.getPointer(32)
                            val targetStr = pTarget?.getWideString(0) ?: "?"
                            print(" -> $targetStr")
                        }
                        println()
                        current = pNext
                        count++
                    } catch (e: Exception) {
                        println("$TAG   (parse error: ${e.message})")
                        break
                    }
                }
                if (count == 0) {
                    println("$TAG   (no records in chain)")
                }
            } else {
                println("$TAG   pDnsRecord is null")
            }
            browseLatch.countDown()
        }
    }
    keepAlive.add(browseCallback)

    val browseQueryName = allocWStr("$SERVICE_TYPE.$DOMAIN")  // "_winpen._tcp.local"
    keepAlive.add(browseQueryName)

    val browseRequest = DnsServiceBrowseRequest().apply {
        Version = DNS_QUERY_REQUEST_VERSION1
        InterfaceIndex = 0
        QueryName = browseQueryName
        pBrowseCallback = browseCallback
        pQueryContext = null
    }
    browseRequest.write()

    // Debug: print struct layout
    println("$TAG BrowseRequest struct size: ${browseRequest.size()}")
    println("$TAG BrowseRequest fields: ${browseRequest.toString()}")

    // DNS_SERVICE_CANCEL: struct with single PVOID (8 bytes on Win64)
    // DnsServiceBrowse requires non-null pCancel (unlike DnsServiceRegister)
    val browseCancel = Memory(8)
    browseCancel.clear()
    keepAlive.add(browseCancel)

    val browseResult = DnsApi.INSTANCE.DnsServiceBrowse(browseRequest, browseCancel)
    println("$TAG DnsServiceBrowse returned: $browseResult (9506=PENDING=success)")

    if (browseResult == DNS_REQUEST_PENDING) {
        println("$TAG Waiting for browse callback (5s timeout)...")
        browseLatch.await(5, TimeUnit.SECONDS)
    }

    println()

    // -------- 3. Resolve --------
    if (regStatus == ERROR_SUCCESS || regStatus == DNS_REQUEST_PENDING) {
        println("$TAG --- Resolve ---")
        println("$TAG Resolving $instanceFullName ...")

        val resolveLatch = CountDownLatch(1)
        var resolveStatus: Int = -1
        val resolveCallback = object : ResolveCallback {
            override fun invoke(status: Int, pQueryContext: Pointer?, pServiceInstance: Pointer?) {
                println("$TAG Resolve callback fired: status=$status (0=success)")
                resolveStatus = status
                if (pServiceInstance != null) {
                    try {
                        val inst = DnsServiceInstance.fromPointer(pServiceInstance)
                        println("$TAG   Instance:  ${inst.pszInstanceName?.getWideString(0) ?: "?"}")
                        println("$TAG   Host:      ${inst.pszHostName?.getWideString(0) ?: "?"}")
                        println("$TAG   Port:      ${inst.wPort.toInt() and 0xFFFF}")
                        println("$TAG   Props:     ${inst.dwPropertyCount}")

                        // Parse TXT properties
                        if (inst.dwPropertyCount > 0 && inst.keys != null && inst.values != null) {
                            val keysBase = inst.keys!!
                            val valuesBase = inst.values!!
                            for (i in 0 until inst.dwPropertyCount) {
                                val kPtr = keysBase.getPointer((i * 8).toLong())
                                val vPtr = valuesBase.getPointer((i * 8).toLong())
                                val k = kPtr?.getWideString(0) ?: "?"
                                val v = vPtr?.getWideString(0) ?: "?"
                                println("$TAG     $k = $v")
                            }
                        }
                    } catch (e: Exception) {
                        println("$TAG   (parse error: ${e.message})")
                        e.printStackTrace()
                    }
                }
                DnsApi.INSTANCE.DnsServiceFreeInstance(pServiceInstance)
                resolveLatch.countDown()
            }
        }
        keepAlive.add(resolveCallback)

        val resolveQueryName = allocWStr(instanceFullName)
        keepAlive.add(resolveQueryName)

        val resolveRequest = DnsServiceResolveRequest().apply {
            Version = DNS_QUERY_REQUEST_VERSION1
            InterfaceIndex = 0
            QueryName = resolveQueryName
            pResolveCompletionCallback = resolveCallback
            pQueryContext = null
        }
        resolveRequest.write()

        // DNS_SERVICE_CANCEL required for DnsServiceResolve too
        val resolveCancel = Memory(8)
        resolveCancel.clear()
        keepAlive.add(resolveCancel)

        val resolveResult = DnsApi.INSTANCE.DnsServiceResolve(resolveRequest, resolveCancel)
        println("$TAG DnsServiceResolve returned: $resolveResult (9506=PENDING=success)")

        if (resolveResult == DNS_REQUEST_PENDING) {
            println("$TAG Waiting for resolve callback (5s timeout)...")
            resolveLatch.await(5, TimeUnit.SECONDS)
        }
        println()
    }

    // -------- 4. Wait --------
    println("$TAG --- Waiting ---")
    println("$TAG Service is registered. You can verify from another device:")
    println("$TAG   macOS:  dns-sd -B _winpen._tcp")
    println("$TAG   iOS:    Use 'Discovery - DNS-SD Browser' app")
    println("$TAG   Linux:  avahi-browse _winpen._tcp")
    println()
    println("$TAG Press Enter to deregister and exit...")

    readlnOrNull()

    // -------- 5. Deregister --------
    println("$TAG Deregistering...")
    val deregRequest = DnsServiceRegisterRequest().apply {
        Version = DNS_QUERY_REQUEST_VERSION1
        InterfaceIndex = 0
        pServiceInstance = pInstance
        pRegisterCompletionCallback = null
        pQueryContext = null
        hCredentials = null
        unicastEnabled = false
    }
    deregRequest.write()

    DnsApi.INSTANCE.DnsServiceDeRegister(deregRequest, null)
    DnsApi.INSTANCE.DnsServiceFreeInstance(pInstance)
    println("$TAG Done.")
}

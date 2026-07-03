package cc.gnode.winpen.spike

import com.sun.jna.*
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// ===== Constants =====
private const val TAG = "[mDNS-Spike]"
private const val SERVICE_TYPE = "_winpen._tcp"
private const val DOMAIN = "local"
private const val INSTANCE_NAME = "WinPen-Spike"
private const val TCP_PORT = 19820
private const val DNS_QUERY_REQUEST_VERSION1 = 1
private const val DNS_TYPE_SRV = 33
private const val DNS_TYPE_TEXT = 16
private const val DNS_TYPE_PTR = 12
private const val ERROR_SUCCESS = 0

// ===== JNA Interface for dnsapi.dll =====
interface DnsApi : Library {
    companion object {
        val INSTANCE: DnsApi = Native.load("Dnsapi", DnsApi::class.java)
    }

    fun DnsServiceRegister(request: DnsServiceRegisterRequest, cancel: Pointer?): Int
    fun DnsServiceDeRegister(request: DnsServiceRegisterRequest, cancel: Pointer?): Int
    fun DnsServiceBrowse(request: DnsServiceBrowseRequest, cancel: Pointer?): Int
    fun DnsServiceResolve(request: DnsServiceResolveRequest, cancel: Pointer?): Int
    fun DnsServiceFreeInstance(instance: Pointer)
    fun DnsRecordListFree(recordList: Pointer, freeType: Int)
}

// ===== Callback interfaces =====
interface RegisterCompleteCallback : Callback {
    fun invoke(status: Int, pQueryContext: Pointer?, pInstance: Pointer?)
}

interface BrowseCallback : Callback {
    fun invoke(status: Int, pQueryContext: Pointer?, pDnsRecord: Pointer?)
}

interface ResolveCompleteCallback : Callback {
    fun invoke(status: Int, pQueryContext: Pointer?, pInstance: Pointer?)
}

// ===== Request structures =====

class DnsServiceRegisterRequest : Structure() {
    @JvmField var version: Int = 0                     // DWORD
    @JvmField var interfaceName: Pointer? = null       // PWSTR
    @JvmField var type: Short = 0                      // WORD
    @JvmField var pQueryContext: Pointer? = null       // PVOID
    @JvmField var pRegisterCompletion: Pointer? = null // function pointer
    @JvmField var pDnsRecord: Pointer? = null          // PDNS_RECORD
    @JvmField var interfaceHandle: Pointer? = null     // HINTERFACE

    override fun getFieldOrder() = listOf(
        "version", "interfaceName", "type", "pQueryContext",
        "pRegisterCompletion", "pDnsRecord", "interfaceHandle"
    )
}

class DnsServiceBrowseRequest : Structure() {
    @JvmField var version: Int = 0                     // DWORD
    @JvmField var interfaceName: Pointer? = null       // PWSTR
    @JvmField var interfaceGuid: ByteArray = ByteArray(16) // GUID
    @JvmField var queryName: Pointer? = null           // PWSTR
    @JvmField var pBrowseCallback: Pointer? = null     // function pointer
    @JvmField var pQueryContext: Pointer? = null       // PVOID

    override fun getFieldOrder() = listOf(
        "version", "interfaceName", "interfaceGuid",
        "queryName", "pBrowseCallback", "pQueryContext"
    )
}

class DnsServiceResolveRequest : Structure() {
    @JvmField var version: Int = 0                     // DWORD
    @JvmField var interfaceName: Pointer? = null       // PWSTR
    @JvmField var interfaceGuid: ByteArray = ByteArray(16) // GUID
    @JvmField var queryName: Pointer? = null           // PWSTR
    @JvmField var pResolveCompletion: Pointer? = null  // function pointer
    @JvmField var pQueryContext: Pointer? = null       // PVOID

    override fun getFieldOrder() = listOf(
        "version", "interfaceName", "interfaceGuid",
        "queryName", "pResolveCompletion", "pQueryContext"
    )
}

// ===== DNS_SERVICE_INSTANCE for parsing resolve results =====

class DnsServiceInstance : Structure {
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

    constructor() : super()
    constructor(p: Pointer) : super(p)

    override fun getFieldOrder() = listOf(
        "pszInstanceName", "pszHostName", "ip4Address", "ip6Address",
        "wPort", "wPriority", "wWeight", "dwPropertyCount", "keys", "values"
    )
}

// ===== Helpers =====

fun allocWStr(s: String): Memory {
    val mem = Memory((s.length + 1) * 2L)
    mem.setWideString(0, s)
    return mem
}

interface Kernel32Lib : Library {
    fun GetComputerNameW(buffer: CharArray, size: IntByReference): Boolean
}

fun getComputerName(): String {
    val kernel32 = Native.load("kernel32", Kernel32Lib::class.java)
    val buf = CharArray(256)
    val size = IntByReference(256)
    kernel32.GetComputerNameW(buf, size)
    return String(buf, 0, size.value)
}

// ===== DNS Record builders (raw memory) =====
// DNS_RECORDW layout on Win64 (8-byte packing):
//   pNext:       offset 0,  8 bytes
//   pName:       offset 8,  8 bytes
//   wType:       offset 16, 2 bytes
//   wDataLength: offset 18, 2 bytes
//   [padding]:   offset 20, 4 bytes
//   Data union:  offset 24, variable
//   dwTtl:       after union (leave 0)
//   dwReserved:  after dwTtl (leave 0)

fun buildSrvRecord(
    instanceFullName: String,
    hostName: String,
    port: Int,
    refs: MutableList<Memory>
): Memory {
    val mem = Memory(80L)
    mem.clear()

    val nameMem = allocWStr(instanceFullName)
    refs.add(nameMem)
    mem.setPointer(8, nameMem)
    mem.setShort(16, DNS_TYPE_SRV.toShort())
    mem.setShort(18, 24)  // sizeof(DNS_SRV_DATA) on Win64

    // SRV data at offset 24
    val targetMem = allocWStr(hostName)
    refs.add(targetMem)
    mem.setPointer(24, targetMem)       // pNameTarget
    mem.setShort(32, 0)                 // wPriority
    mem.setShort(34, 0)                 // wWeight
    mem.setShort(36, port.toShort())    // wPort
    // pReserved = null (already cleared)

    return mem
}

fun buildTxtRecord(
    instanceFullName: String,
    txtStrings: List<String>,
    refs: MutableList<Memory>
): Memory {
    val mem = Memory(80L)
    mem.clear()

    val nameMem = allocWStr(instanceFullName)
    refs.add(nameMem)
    mem.setPointer(8, nameMem)
    mem.setShort(16, DNS_TYPE_TEXT.toShort())

    val count = txtStrings.size
    // DNS_TXT_DATAW: dwStringCount(4) + padding(4) + count * pointer(8)
    mem.setShort(18, (8 + count * 8).toShort())

    mem.setInt(24, count)  // dwStringCount

    txtStrings.forEachIndexed { i, str ->
        val strMem = allocWStr(str)
        refs.add(strMem)
        mem.setPointer((32 + i * 8).toLong(), strMem)
    }

    return mem
}

// ===== Main =====

fun main() {
    println("$TAG === WinPen mDNS Spike ===")
    println()

    val computerName = getComputerName()
    val hostName = "$computerName.local"
    val instanceFullName = "$INSTANCE_NAME.$SERVICE_TYPE.$DOMAIN"

    println("$TAG Computer name:    $computerName")
    println("$TAG Host name:        $hostName")
    println("$TAG Service instance: $instanceFullName")
    println("$TAG TCP port:         $TCP_PORT")
    println()

    // Keep all native memory + callback references alive
    val refs = mutableListOf<Memory>()
    val callbacks = mutableListOf<Callback>()

    // ===== Register =====
    println("$TAG --- Registration ---")
    println("$TAG [OBSERVE] Watch for Windows Firewall popup!")
    println("$TAG [OBSERVE] If popup appears, note which port it mentions (5353 vs 19820)")
    println()

    val srvRecord = buildSrvRecord(instanceFullName, hostName, TCP_PORT, refs)
    val txtRecord = buildTxtRecord(
        instanceFullName,
        listOf("ver=1", "name=$computerName", "os=win"),
        refs
    )

    // Link SRV.pNext = TXT
    srvRecord.setPointer(0, txtRecord)

    val registerLatch = CountDownLatch(1)
    var registerStatus = -1

    val registerCallback = object : RegisterCompleteCallback {
        override fun invoke(status: Int, pQueryContext: Pointer?, pInstance: Pointer?) {
            registerStatus = status
            println("$TAG Register callback fired: status=$status (0=success)")
            if (pInstance != null) {
                try {
                    val inst = DnsServiceInstance(pInstance)
                    val name = inst.pszInstanceName?.getWideString(0) ?: "?"
                    println("$TAG   Registered instance: $name")
                } catch (e: Exception) {
                    println("$TAG   (could not parse instance: ${e.message})")
                }
            }
            registerLatch.countDown()
        }
    }
    callbacks.add(registerCallback)

    val registerRequest = DnsServiceRegisterRequest().apply {
        version = DNS_QUERY_REQUEST_VERSION1
        interfaceName = null
        type = 1  // per MS docs: DNS_QUERY_REQUEST_VERSION1
        pQueryContext = null
        pRegisterCompletion = CallbackReference.getFunctionPointer(registerCallback)
        pDnsRecord = srvRecord
        interfaceHandle = null
    }
    registerRequest.write()

    println("$TAG Calling DnsServiceRegister...")
    var regResult = DnsApi.INSTANCE.DnsServiceRegister(registerRequest, null)
    println("$TAG DnsServiceRegister returned: $regResult (0=success)")

    if (regResult != ERROR_SUCCESS) {
        println("$TAG Type=1 failed, trying Type=0...")
        registerRequest.type = 0
        registerRequest.write()
        regResult = DnsApi.INSTANCE.DnsServiceRegister(registerRequest, null)
        println("$TAG DnsServiceRegister(Type=0) returned: $regResult")
    }

    if (regResult == ERROR_SUCCESS) {
        if (!registerLatch.await(5, TimeUnit.SECONDS)) {
            println("$TAG WARNING: Register callback not received within 5s")
            println("$TAG (may be normal — service might register silently)")
        }
    }

    println()

    // ===== Browse =====
    println("$TAG --- Browse ---")

    val browseQuery = "$SERVICE_TYPE.$DOMAIN"
    val browseQueryMem = allocWStr(browseQuery)
    refs.add(browseQueryMem)

    val browseCallback = object : BrowseCallback {
        override fun invoke(status: Int, pQueryContext: Pointer?, pDnsRecord: Pointer?) {
            if (pDnsRecord == null) {
                println("$TAG Browse: complete (end of results)")
                return
            }

            println("$TAG Browse callback: status=$status")

            var recordPtr: Pointer? = pDnsRecord
            while (recordPtr != null) {
                val wType = recordPtr.getShort(16).toInt() and 0xFFFF
                val pName = recordPtr.getPointer(8)
                val name = pName?.getWideString(0) ?: "?"

                when (wType) {
                    DNS_TYPE_PTR -> {
                        val pNameHost = recordPtr.getPointer(24)
                        val hostName = pNameHost?.getWideString(0) ?: "?"
                        println("$TAG   PTR: $name -> $hostName")
                        resolveService(hostName, refs, callbacks)
                    }
                    else -> {
                        println("$TAG   Type $wType: $name")
                    }
                }

                recordPtr = recordPtr.getPointer(0) // pNext
            }

            DnsApi.INSTANCE.DnsRecordListFree(pDnsRecord, 1) // DnsFreeRecordListDeep
        }
    }
    callbacks.add(browseCallback)

    val browseRequest = DnsServiceBrowseRequest().apply {
        version = DNS_QUERY_REQUEST_VERSION1
        interfaceName = null
        interfaceGuid = ByteArray(16)
        queryName = browseQueryMem
        pBrowseCallback = CallbackReference.getFunctionPointer(browseCallback)
        pQueryContext = null
    }
    browseRequest.write()

    println("$TAG Browsing for $browseQuery...")
    val browseResult = DnsApi.INSTANCE.DnsServiceBrowse(browseRequest, null)
    println("$TAG DnsServiceBrowse returned: $browseResult (0=success)")

    // Wait for browse results
    Thread.sleep(5000)

    println()

    // ===== Wait for user =====
    println("$TAG --- Waiting ---")
    println("$TAG Service is registered. You can verify from another device:")
    println("$TAG   macOS:  dns-sd -B _winpen._tcp")
    println("$TAG   iOS:    Use 'Discovery - DNS-SD Browser' app")
    println("$TAG   Linux:  avahi-browse _winpen._tcp")
    println()
    println("$TAG Press Enter to deregister and exit...")
    readLine()

    // ===== Deregister =====
    println("$TAG Deregistering...")
    registerRequest.type = 1 // DnsServiceDeRegisterRequest
    registerRequest.write()
    DnsApi.INSTANCE.DnsServiceDeRegister(registerRequest, null)

    Thread.sleep(1000)
    println("$TAG Done.")
}

// ===== Resolve =====

fun resolveService(
    instanceName: String,
    refs: MutableList<Memory>,
    callbacks: MutableList<Callback>
) {
    println("$TAG   Resolving $instanceName ...")

    val resolveLatch = CountDownLatch(1)

    val resolveCallback = object : ResolveCompleteCallback {
        override fun invoke(status: Int, pQueryContext: Pointer?, pInstance: Pointer?) {
            println("$TAG   Resolve callback: status=$status")

            if (pInstance != null) {
                try {
                    val inst = DnsServiceInstance(pInstance)

                    val name = inst.pszInstanceName?.getWideString(0) ?: "?"
                    val host = inst.pszHostName?.getWideString(0) ?: "?"
                    val port = inst.wPort.toInt() and 0xFFFF

                    println("$TAG     Instance: $name")
                    println("$TAG     Host:     $host")
                    println("$TAG     Port:     $port")

                    // IPv4
                    inst.ip4Address?.let { ip4Ptr ->
                        val a = ip4Ptr.getByte(0).toInt() and 0xFF
                        val b = ip4Ptr.getByte(1).toInt() and 0xFF
                        val c = ip4Ptr.getByte(2).toInt() and 0xFF
                        val d = ip4Ptr.getByte(3).toInt() and 0xFF
                        println("$TAG     IPv4:     $a.$b.$c.$d")
                    }

                    // TXT properties
                    val propCount = inst.dwPropertyCount
                    if (propCount > 0 && inst.keys != null && inst.values != null) {
                        println("$TAG     TXT records ($propCount):")
                        val keys = inst.keys!!.getPointerArray(0, propCount)
                        val values = inst.values!!.getPointerArray(0, propCount)
                        for (i in 0 until propCount) {
                            val k = keys[i]?.getWideString(0) ?: "?"
                            val v = values[i]?.getWideString(0) ?: "?"
                            println("$TAG       $k = $v")
                        }
                    } else {
                        println("$TAG     TXT records: (none)")
                    }
                } catch (e: Exception) {
                    println("$TAG     (parse error: ${e.message})")
                    e.printStackTrace()
                }

                DnsApi.INSTANCE.DnsServiceFreeInstance(pInstance)
            }

            resolveLatch.countDown()
        }
    }
    callbacks.add(resolveCallback)

    val queryMem = allocWStr(instanceName)
    refs.add(queryMem)

    val resolveRequest = DnsServiceResolveRequest().apply {
        version = DNS_QUERY_REQUEST_VERSION1
        interfaceName = null
        interfaceGuid = ByteArray(16)
        queryName = queryMem
        pResolveCompletion = CallbackReference.getFunctionPointer(resolveCallback)
        pQueryContext = null
    }
    resolveRequest.write()

    val result = DnsApi.INSTANCE.DnsServiceResolve(resolveRequest, null)
    if (result != ERROR_SUCCESS) {
        println("$TAG   DnsServiceResolve failed: $result")
    }

    resolveLatch.await(5, TimeUnit.SECONDS)
}

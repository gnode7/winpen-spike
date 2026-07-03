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

// ===== Request structures (matching Windows SDK windns.h) =====
// Win64 layout: DWORD(4) + pad(4) + PWSTR(8) + WORD(2) + pad(6) + PVOID(8) + fnptr(8) + PDNS_RECORD(8) = 48 bytes

class DnsServiceRegisterRequest : Structure() {
    @JvmField var version: Int = 0                     // DWORD   offset 0
    @JvmField var interfaceName: Pointer? = null       // PWSTR   offset 8 (4 bytes padding before)
    @JvmField var type: Short = 0                      // WORD    offset 16
    @JvmField var pQueryContext: Pointer? = null       // PVOID   offset 24 (6 bytes padding before)
    @JvmField var pRegisterCompletion: Pointer? = null // fnptr   offset 32
    @JvmField var pDnsRecord: Pointer? = null          // PDNS_RECORD offset 40

    override fun getFieldOrder() = listOf(
        "version", "interfaceName", "type", "pQueryContext",
        "pRegisterCompletion", "pDnsRecord"
    )
}

// Win64 layout: DWORD(4) + pad(4) + PWSTR(8) + PVOID(8) + fnptr(8) = 32 bytes
class DnsServiceBrowseRequest : Structure() {
    @JvmField var version: Int = 0                     // DWORD   offset 0
    @JvmField var interfaceName: Pointer? = null       // PWSTR   offset 8
    @JvmField var pQueryContext: Pointer? = null       // PVOID   offset 16
    @JvmField var pBrowseCallback: Pointer? = null     // fnptr   offset 24

    override fun getFieldOrder() = listOf(
        "version", "interfaceName", "pQueryContext", "pBrowseCallback"
    )
}

// Win64 layout: same as browse = 32 bytes
class DnsServiceResolveRequest : Structure() {
    @JvmField var version: Int = 0                     // DWORD   offset 0
    @JvmField var interfaceName: Pointer? = null       // PWSTR   offset 8
    @JvmField var pQueryContext: Pointer? = null       // PVOID   offset 16
    @JvmField var pResolveCompletion: Pointer? = null  // fnptr   offset 24

    override fun getFieldOrder() = listOf(
        "version", "interfaceName", "pQueryContext", "pResolveCompletion"
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
//
// DNS_RECORDW layout on Win64:
//   pNext:       offset 0,  8 bytes (pointer)
//   pName:       offset 8,  8 bytes (pointer)
//   wType:       offset 16, 2 bytes
//   wDataLength: offset 18, 2 bytes
//   Flags:       offset 20, 4 bytes (union DW/FLAGS)
//   dwTtl:       offset 24, 4 bytes
//   dwReserved:  offset 28, 4 bytes
//   Data:        offset 32, variable (union)
//
// DNS_SRV_DATAW layout on Win64:
//   pNameTarget: offset 0,  8 bytes (pointer)
//   wPriority:   offset 8,  2 bytes
//   wWeight:     offset 10, 2 bytes
//   wPort:       offset 12, 2 bytes
//   (padding):   offset 14, 2 bytes
//   pReserved:   offset 16, 8 bytes (pointer)
//   Total: 24 bytes
//
// DNS_TXT_DATAW layout on Win64:
//   dwStringCount:  offset 0,  4 bytes
//   (padding):      offset 4,  4 bytes
//   pStringArray[]: offset 8+, 8 bytes each (pointer)
//   Total: 8 + count * 8 bytes

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
    mem.setPointer(8, nameMem)                   // pName
    mem.setShort(16, DNS_TYPE_SRV.toShort())     // wType
    mem.setShort(18, 24)                          // wDataLength = sizeof(DNS_SRV_DATAW) = 24

    // Data starts at offset 32
    val targetMem = allocWStr(hostName)
    refs.add(targetMem)
    mem.setPointer(32, targetMem)                // Data.SRV.pNameTarget
    mem.setShort(40, 0)                          // Data.SRV.wPriority
    mem.setShort(42, 0)                          // Data.SRV.wWeight
    mem.setShort(44, port.toShort())             // Data.SRV.wPort
    // Data.SRV.pReserved at offset 48 = null (already cleared)

    return mem
}

fun buildTxtRecord(
    instanceFullName: String,
    txtStrings: List<String>,
    refs: MutableList<Memory>
): Memory {
    val count = txtStrings.size
    val txtDataSize = 8 + count * 8              // DNS_TXT_DATAW size on Win64

    val mem = Memory(80L)
    mem.clear()

    val nameMem = allocWStr(instanceFullName)
    refs.add(nameMem)
    mem.setPointer(8, nameMem)                   // pName
    mem.setShort(16, DNS_TYPE_TEXT.toShort())    // wType
    mem.setShort(18, txtDataSize.toShort())      // wDataLength

    // Data starts at offset 32
    // DNS_TXT_DATAW:
    //   dwStringCount at offset 32
    //   pStringArray[0] at offset 40
    //   pStringArray[1] at offset 48
    mem.setInt(32, count)                         // Data.TXT.dwStringCount

    txtStrings.forEachIndexed { i, str ->
        val strMem = allocWStr(str)
        refs.add(strMem)
        mem.setPointer((40 + i * 8).toLong(), strMem)  // Data.TXT.pStringArray[i]
    }

    return mem
}

// ===== Main =====

fun main() {
    println("$TAG === WinPen mDNS Spike v2 ===")
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

    // Try Type=0 (DNS_SERVICE_REGISTER_REQUEST_WORD_TYPE per SDK)
    val registerRequest = DnsServiceRegisterRequest().apply {
        version = DNS_QUERY_REQUEST_VERSION1
        interfaceName = null
        type = 0
        pQueryContext = null
        pRegisterCompletion = CallbackReference.getFunctionPointer(registerCallback)
        pDnsRecord = srvRecord
    }
    registerRequest.write()

    println("$TAG Calling DnsServiceRegister(Type=0)...")
    var regResult = DnsApi.INSTANCE.DnsServiceRegister(registerRequest, null)
    println("$TAG DnsServiceRegister returned: $regResult (0=success)")

    if (regResult != ERROR_SUCCESS) {
        println("$TAG Type=0 failed, trying Type=1...")
        registerRequest.type = 1
        registerRequest.write()
        regResult = DnsApi.INSTANCE.DnsServiceRegister(registerRequest, null)
        println("$TAG DnsServiceRegister(Type=1) returned: $regResult")
    }

    if (regResult == ERROR_SUCCESS) {
        println("$TAG Waiting for register callback (5s)...")
        if (!registerLatch.await(5, TimeUnit.SECONDS)) {
            println("$TAG WARNING: Register callback not received within 5s")
            println("$TAG (service may still be registered — check from another device)")
        }
    }

    println()

    // ===== Browse (self-discovery test) =====
    println("$TAG --- Browse ---")
    println("$TAG NOTE: DnsServiceBrowse has no query name field in the struct.")
    println("$TAG It browses for ALL DNS-SD services. We'll filter for _winpen._tcp in callback.")

    val browseCallback = object : BrowseCallback {
        override fun invoke(status: Int, pQueryContext: Pointer?, pDnsRecord: Pointer?) {
            println("$TAG Browse callback: status=$status")

            if (pDnsRecord == null) {
                println("$TAG   (end of results)")
                return
            }

            var recordPtr: Pointer? = pDnsRecord
            while (recordPtr != null) {
                val wType = recordPtr.getShort(16).toInt() and 0xFFFF
                val pName = recordPtr.getPointer(8)
                val name = pName?.getWideString(0) ?: "?"

                when (wType) {
                    DNS_TYPE_PTR -> {
                        // PTR data at offset 32: pNameHost (pointer)
                        val pNameHost = recordPtr.getPointer(32)
                        val hostName = pNameHost?.getWideString(0) ?: "?"
                        if (name.contains("_winpen")) {
                            println("$TAG   PTR: $name -> $hostName  *** WINPEN ***")
                            resolveService(hostName, refs, callbacks)
                        } else {
                            println("$TAG   PTR: $name -> $hostName  (other service)")
                        }
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
        pQueryContext = null
        pBrowseCallback = CallbackReference.getFunctionPointer(browseCallback)
    }
    browseRequest.write()

    println("$TAG Browsing for DNS-SD services...")
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
    // For deregister, Type=1 per MS docs (DNS_SERVICE_DEREGISTER_REQUEST_WORD_TYPE)
    registerRequest.type = 1
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

    // DnsServiceResolveRequest has no query name field.
    // The instance name to resolve might need to go through InterfaceName
    // or there may be additional fields not in the MinGW headers.
    // Try setting interfaceName to the instance name as a workaround.
    val queryMem = allocWStr(instanceName)
    refs.add(queryMem)

    val resolveRequest = DnsServiceResolveRequest().apply {
        version = DNS_QUERY_REQUEST_VERSION1
        interfaceName = queryMem  // try using instance name here
        pQueryContext = null
        pResolveCompletion = CallbackReference.getFunctionPointer(resolveCallback)
    }
    resolveRequest.write()

    val result = DnsApi.INSTANCE.DnsServiceResolve(resolveRequest, null)
    if (result != ERROR_SUCCESS) {
        println("$TAG   DnsServiceResolve returned: $result (0=success)")
    }

    resolveLatch.await(5, TimeUnit.SECONDS)
}

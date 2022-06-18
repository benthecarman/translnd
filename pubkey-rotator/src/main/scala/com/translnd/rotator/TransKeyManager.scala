package com.translnd.rotator

import com.translnd.rotator.config.TransLndAppConfig
import com.translnd.rotator.db.InvoiceDAO
import grizzled.slf4j.Logging
import org.bitcoins.core.crypto.ExtKeyVersion.SegWitMainNetPriv
import org.bitcoins.core.crypto.ExtPrivateKeyHardened
import org.bitcoins.core.hd._
import org.bitcoins.crypto._
import org.bitcoins.keymanager._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent._
import scala.concurrent.duration.DurationInt

class TransKeyManager()(implicit
    ec: ExecutionContext,
    val config: TransLndAppConfig)
    extends Logging {

  private[rotator] val invoiceDAO = InvoiceDAO()

  /** The root private key for this coordinator */
  private[this] lazy val extPrivateKey: ExtPrivateKeyHardened = {
    WalletStorage.getPrivateKeyFromDisk(config.kmConf.seedPath,
                                        SegWitMainNetPriv,
                                        config.aesPasswordOpt,
                                        config.bip39PasswordOpt)
  }

  private lazy val counter: AtomicInteger = {
    val f = invoiceDAO.maxIndex().map {
      case Some(idx) => new AtomicInteger(idx + 1)
      case None      => new AtomicInteger(0)
    }
    Await.result(f, 10.seconds)
  }

  private def getPath(idx: Int): BIP32Path = {
    val purpose = HDPurposes.SegWit
    val coin = HDCoin(purpose, HDCoinType.Bitcoin)
    val account = HDAccount(coin, 0)
    val hdChain = HDChain(HDChainType.External, account)
    val path = HDAddress(hdChain, idx).toPath
    val hardened = path.map(_.copy(hardened = true))

    BIP32Path(hardened.toVector)
  }

  final private[rotator] def getKey(idx: Int): ECPrivateKey = {
    val path = getPath(idx)
    extPrivateKey.deriveChildPrivKey(path).key
  }

  final private[rotator] def nextKey(): (ECPrivateKey, Int) = {
    val idx = counter.getAndIncrement()
    (getKey(idx), idx)
  }
}

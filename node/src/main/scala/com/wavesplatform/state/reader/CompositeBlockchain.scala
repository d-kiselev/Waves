package com.wavesplatform.state.reader

import cats.implicits._
import cats.kernel.Monoid
import com.wavesplatform.account.{Address, Alias}
import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.block.{Block, BlockHeader}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.lang.script.Script
import com.wavesplatform.settings.BlockchainSettings
import com.wavesplatform.state._
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxValidationError.{AliasDoesNotExist, AliasIsDisabled, GenericError}
import com.wavesplatform.transaction.assets.IssueTransaction
import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.{Asset, Transaction, TransactionParser}
import com.wavesplatform.utils.CloseableIterator

final case class CompositeBlockchain(inner: Blockchain, maybeDiff: Option[Diff] = None, newBlock: Option[Block] = None, carry: Long = 0) extends Blockchain {
  override val settings: BlockchainSettings = inner.settings

  def diff: Diff = maybeDiff.getOrElse(Diff.empty)

  override def portfolio(a: Address): Portfolio = inner.portfolio(a).combine(diff.portfolios.getOrElse(a, Portfolio.empty))

  override def balance(address: Address, assetId: Asset): Long =
    inner.balance(address, assetId) + diff.portfolios.getOrElse(address, Portfolio.empty).balanceOf(assetId)

  override def leaseBalance(address: Address): LeaseBalance = {
    cats.Monoid.combine(inner.leaseBalance(address), diff.portfolios.getOrElse(address, Portfolio.empty).lease)
  }

  override def assetScript(asset: IssuedAsset): Option[Script] = maybeDiff.flatMap(_.assetScripts.get(asset)).getOrElse(inner.assetScript(asset))

  override def hasAssetScript(asset: IssuedAsset): Boolean = maybeDiff.flatMap(_.assetScripts.get(asset)) match {
    case Some(s) => s.nonEmpty
    case None    => inner.hasAssetScript(asset)
  }

  override def assetDescription(asset: IssuedAsset): Option[AssetDescription] = {
    val script: Option[Script] = assetScript(asset)
    inner.assetDescription(asset) match {
      case Some(ad) =>
        diff.issuedAssets
          .get(asset)
          .map { newAssetInfo =>
            val oldAssetInfo = AssetInfo(ad.reissuable, ad.totalVolume)
            val combination  = Monoid.combine(oldAssetInfo, newAssetInfo)
            ad.copy(reissuable = combination.isReissuable, totalVolume = combination.volume, script = script)
          }
          .orElse(Some(ad.copy(script = script)))
          .map { ad =>
            diff.sponsorship.get(asset).fold(ad) {
              case SponsorshipValue(sponsorship) =>
                ad.copy(sponsorship = sponsorship)
              case SponsorshipNoInfo =>
                ad
            }
          }
      case None =>
        val sponsorship = diff.sponsorship.get(asset).fold(0L) {
          case SponsorshipValue(sp) => sp
          case SponsorshipNoInfo    => 0L
        }
        diff.transactions
          .get(asset.id)
          .collectFirst {
            case (it: IssueTransaction, _) =>
              AssetDescription(it.sender, it.name, it.description, it.decimals, it.reissuable, it.quantity, script, sponsorship)
          }
          .map(z => diff.issuedAssets.get(asset).fold(z)(r => z.copy(reissuable = r.isReissuable, totalVolume = r.volume, script = script)))
    }
  }

  override def leaseDetails(leaseId: ByteStr): Option[LeaseDetails] = {
    inner.leaseDetails(leaseId).map(ld => ld.copy(isActive = diff.leaseState.getOrElse(leaseId, ld.isActive))) orElse
      diff.transactions.get(leaseId).collect {
        case (lt: LeaseTransaction, _) =>
          LeaseDetails(lt.sender, lt.recipient, this.height, lt.amount, diff.leaseState(lt.id()))
      }
  }

  override def transactionInfo(id: ByteStr): Option[(Int, Transaction)] =
    diff.transactions
      .get(id)
      .map(t => (this.height, t._1))
      .orElse(inner.transactionInfo(id))

  override def transactionHeight(id: ByteStr): Option[Int] =
    diff.transactions
      .get(id)
      .map(_ => this.height)
      .orElse(inner.transactionHeight(id))

  override def height: Int = inner.height + newBlock.fold(0)(_ => 1)

  override def nftList(address: Address, from: Option[IssuedAsset]): CloseableIterator[IssueTransaction] = {
    nftListFromDiff(inner, maybeDiff)(address, from)
  }

  override def addressTransactions(address: Address,
                                   types: Set[TransactionParser],
                                   fromId: Option[ByteStr]): CloseableIterator[(Height, Transaction)] = {

    val fromDiff = maybeDiff
      .fold(CloseableIterator.empty[(Height, Transaction, Set[Address])]) { diff =>
        diff
          .reverseIterator(_.transactions)
          .map {
            case (_, (tx, addrs)) => (Height @@ this.height, tx, addrs)
          }
      }

    addressTransactionsCompose(inner, fromDiff)(address, types, fromId)
  }

  override def resolveAlias(alias: Alias): Either[ValidationError, Address] = inner.resolveAlias(alias) match {
    case l @ Left(AliasIsDisabled(_)) => l
    case Right(addr)                  => Right(diff.aliases.getOrElse(alias, addr))
    case Left(_)                      => diff.aliases.get(alias).toRight(AliasDoesNotExist(alias))
  }

  override def allActiveLeases: CloseableIterator[LeaseTransaction] = {
    val (active, canceled) = diff.leaseState.partition(_._2)
    val fromDiff = active.keysIterator
      .map(id => diff.transactions(id)._1)
      .collect { case lt: LeaseTransaction => lt }

    val fromInner = inner.allActiveLeases.filterNot(ltx => canceled.keySet.contains(ltx.id()))
    CloseableIterator.seq(fromDiff, fromInner)
  }

  override def collectLposPortfolios[A](pf: PartialFunction[(Address, Portfolio), A]): Map[Address, A] = {
    val b = Map.newBuilder[Address, A]
    for ((a, p) <- diff.portfolios if p.lease != LeaseBalance.empty || p.balance != 0) {
      pf.runWith(b += a -> _)(a -> this.wavesPortfolio(a))
    }

    inner.collectLposPortfolios(pf) ++ b.result()
  }

  override def invokeScriptResult(txId: TransactionId): Either[ValidationError, InvokeScriptResult] = {
    diff.scriptResults
      .get(txId)
      .toRight(GenericError("InvokeScript result not found"))
      .orElse(inner.invokeScriptResult(txId))
  }

  override def containsTransaction(tx: Transaction): Boolean = diff.transactions.contains(tx.id()) || inner.containsTransaction(tx)

  override def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee =
    diff.orderFills.get(orderId).orEmpty.combine(inner.filledVolumeAndFee(orderId))

  override def balanceSnapshots(address: Address, from: Int, to: BlockId): Seq[BalanceSnapshot] = {
    if (inner.heightOf(to).isDefined || maybeDiff.isEmpty) {
      inner.balanceSnapshots(address, from, to)
    } else {
      val balance = this.balance(address)
      val lease   = this.leaseBalance(address)
      val bs      = BalanceSnapshot(height, Portfolio(balance, lease, Map.empty))
      if (inner.height > 0 && from < this.height) bs +: inner.balanceSnapshots(address, from, to) else Seq(bs)
    }
  }

  override def accountScript(address: Address): Option[Script] = {
    diff.scripts.get(address) match {
      case None            => inner.accountScript(address)
      case Some(None)      => None
      case Some(Some(scr)) => Some(scr)
    }
  }

  override def hasScript(address: Address): Boolean = {
    diff.scripts.get(address) match {
      case None          => inner.hasScript(address)
      case Some(None)    => false
      case Some(Some(_)) => true
    }
  }

  override def accountDataKeys(acc: Address): Seq[String] = {
    val fromInner = inner.accountDataKeys(acc)
    val fromDiff  = diff.accountData.get(acc).toSeq.flatMap(_.data.keys)
    (fromInner ++ fromDiff).distinct
  }

  override def accountData(acc: Address): AccountDataInfo = {
    val fromInner = inner.accountData(acc)
    val fromDiff  = diff.accountData.get(acc).orEmpty
    fromInner.combine(fromDiff)
  }

  override def accountData(acc: Address, key: String): Option[DataEntry[_]] = {
    val diffData = diff.accountData.get(acc).orEmpty
    diffData.data.get(key).orElse(inner.accountData(acc, key))
  }

  private def changedBalances(pred: Portfolio => Boolean, f: Address => Long): Map[Address, Long] =
    for {
      (address, p) <- diff.portfolios
      if pred(p)
    } yield address -> f(address)

  override def assetDistribution(assetId: IssuedAsset): AssetDistribution = {
    val fromInner = inner.assetDistribution(assetId)
    val fromDiff  = AssetDistribution(changedBalances(_.assets.getOrElse(assetId, 0L) != 0, balance(_, assetId)))

    fromInner |+| fromDiff
  }

  override def assetDistributionAtHeight(asset: IssuedAsset,
                                         height: Int,
                                         count: Int,
                                         fromAddress: Option[Address]): Either[ValidationError, AssetDistributionPage] = {
    inner.assetDistributionAtHeight(asset, height, count, fromAddress)
  }

  override def wavesDistribution(height: Int): Either[ValidationError, Map[Address, Long]] = {
    val innerDistribution = inner.wavesDistribution(height)
    if (height < this.height) innerDistribution
    else {
      innerDistribution.map(_ ++ changedBalances(_.balance != 0, balance(_)))
    }
  }

  override def lastBlock: Option[Block] = newBlock.orElse(inner.lastBlock)

  override def carryFee: Long = carry

  override def score: BigInt = newBlock.fold(BigInt(0))(_.blockScore()) + inner.score

  private def filterById(blockId: BlockId): Option[Block] = newBlock.filter(_.uniqueId == blockId)
  private def filterByHeight(height: Int): Option[Block]  = newBlock.filter(_ => this.height == height)

  private def headerAndSize(block: Block): (BlockHeader, Int) = block -> block.bytes().length

  override def blockHeaderAndSize(height: Int): Option[(BlockHeader, Int)] =
    filterByHeight(height).map(headerAndSize) orElse inner.blockHeaderAndSize(height)
  override def blockHeaderAndSize(blockId: ByteStr): Option[(BlockHeader, Int)] =
    filterById(blockId).map(headerAndSize) orElse inner.blockHeaderAndSize(blockId)

  override def blockBytes(height: Int): Option[Array[Byte]]      = filterByHeight(height).map(_.bytes()) orElse inner.blockBytes(height)
  override def blockBytes(blockId: ByteStr): Option[Array[Byte]] = filterById(blockId).map(_.bytes()) orElse inner.blockBytes(blockId)

  override def heightOf(blockId: ByteStr): Option[Int] = filterById(blockId).map(_ => height) orElse inner.heightOf(blockId)

  /** Returns the most recent block IDs, starting from the most recent  one */
  override def lastBlockIds(howMany: Int): Seq[ByteStr] =
    if (howMany <= 0) Seq.empty else newBlock.map(_.uniqueId).toSeq ++ inner.lastBlockIds(howMany - 1)

  /** Returns a chain of blocks starting with the block with the given ID (from oldest to newest) */
  override def blockIdsAfter(parentSignature: ByteStr, howMany: Int): Option[Seq[ByteStr]] =
    for {
      ids <- inner.blockIdsAfter(parentSignature, howMany)
      newId = newBlock.filter(_.reference == parentSignature).map(_.uniqueId).fold(Seq.empty[ByteStr])(Seq(_))
    } yield newId ++ ids

  override def parentHeader(block: BlockHeader, back: Int): Option[BlockHeader] = inner.parentHeader(block, back)

  override def totalFee(height: Int): Option[Long] = inner.totalFee(height)

  /** Features related */
  override def approvedFeatures: Map[Short, Int] = inner.approvedFeatures

  override def activatedFeatures: Map[Short, Int] = inner.activatedFeatures

  override def featureVotes(height: Int): Map[Short, Int] = inner.featureVotes(height)
}

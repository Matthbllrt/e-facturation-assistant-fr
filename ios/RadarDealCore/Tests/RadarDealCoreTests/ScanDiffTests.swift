import XCTest
@testable import RadarDealCore

/// The comparison engine — the heart of the product. These mirror the Android `ScanDiffTest`
/// case for case, so a rule can never drift between the two platforms without a test failing.
final class ScanDiffTests: XCTestCase {

    private let watchID: Int64 = 42
    private let now = Date(timeIntervalSince1970: 1_700_000_000)

    private func raw(
        _ id: String,
        price: Double? = 50,
        title: String? = "Nike Air Max 95",
        brand: String? = "Nike",
        size: String? = "42",
        imageURL: String? = "https://img/x.jpg"
    ) -> RawListing {
        RawListing(itemID: id, title: title, brand: brand, size: size,
                   condition: "Très bon état", price: price, currency: "EUR",
                   imageURL: imageURL, itemURL: "https://www.vinted.fr/items/\(id)")
    }

    private func stored(
        _ id: String,
        price: Double? = 50,
        isFavorite: Bool = false,
        previousPrice: Double? = nil,
        priceDroppedAt: Date? = nil,
        firstSeenAt: Date? = nil
    ) -> Listing {
        Listing(itemID: id, watchID: watchID, title: "Nike Air Max 95", brand: "Nike",
                size: "42", condition: "Très bon état", price: price, currency: "EUR",
                previousPrice: previousPrice, imageURL: "https://img/x.jpg",
                itemURL: "https://www.vinted.fr/items/\(id)",
                firstSeenAt: firstSeenAt ?? now.addingTimeInterval(-100),
                lastSeenAt: now.addingTimeInterval(-100),
                isFavorite: isFavorite, priceDroppedAt: priceDroppedAt)
    }

    private func known(_ listings: [Listing]) -> [String: Listing] {
        Dictionary(uniqueKeysWithValues: listings.map { ($0.itemID, $0) })
    }

    // MARK: - Baseline

    func testFirstScanStoresEverythingAndFlagsNothing() {
        let incoming = (1...52).map { raw("\($0)") }
        let result = ScanDiff.diff(watchID: watchID, known: [:], incoming: incoming,
                                   baselineDone: false, now: now)

        XCTAssertEqual(result.listings.count, 52)
        XCTAssertTrue(result.newListings.isEmpty)
        XCTAssertFalse(result.listings.contains { $0.isNew })
        XCTAssertTrue(result.priceDrops.isEmpty)
    }

    func testSecondScanReportsOnlyGenuinelyNewListings() {
        let baseline = (1...52).map { stored("\($0)") }
        let incoming = (1...54).map { raw("\($0)") }

        let result = ScanDiff.diff(watchID: watchID, known: known(baseline),
                                   incoming: incoming, baselineDone: true, now: now)

        XCTAssertEqual(result.newListings.count, 2)
        XCTAssertEqual(Set(result.newListings.map(\.itemID)), ["53", "54"])
        XCTAssertEqual(result.listings.filter(\.isNew).count, 2)
    }

    func testNewListingRecordsFirstSeenAndIsMarkedNew() {
        let result = ScanDiff.diff(watchID: watchID, known: [:], incoming: [raw("1")],
                                   baselineDone: true, now: now)
        let listing = try! XCTUnwrap(result.newListings.first)

        XCTAssertTrue(listing.isNew)
        XCTAssertEqual(listing.firstSeenAt, now)
        XCTAssertEqual(listing.lastSeenAt, now)
    }

    // MARK: - Price drops

    func testDetectsPriceDropAndRecordsPreviousPrice() {
        let result = ScanDiff.diff(watchID: watchID, known: known([stored("1", price: 70)]),
                                   incoming: [raw("1", price: 55)],
                                   baselineDone: true, now: now)

        let drop = try! XCTUnwrap(result.priceDrops.first)
        XCTAssertEqual(drop.oldPrice, 70)
        XCTAssertEqual(drop.newPrice, 55)
        XCTAssertEqual(drop.ratio, 0.214, accuracy: 0.001)

        let entity = try! XCTUnwrap(result.listings.first)
        XCTAssertEqual(entity.price, 55)
        XCTAssertEqual(entity.previousPrice, 70)
        XCTAssertEqual(entity.priceDroppedAt, now)
    }

    func testPriceIncreaseIsRecordedButNeverAnnouncedAsADrop() {
        let result = ScanDiff.diff(watchID: watchID, known: known([stored("1", price: 40)]),
                                   incoming: [raw("1", price: 60)],
                                   baselineDone: true, now: now)

        XCTAssertTrue(result.priceDrops.isEmpty)
        XCTAssertEqual(result.listings.first?.previousPrice, 40)
        XCTAssertNil(result.listings.first?.priceDroppedAt)
    }

    func testUnchangedPriceProducesNoDropAndNoPricePoint() {
        let result = ScanDiff.diff(watchID: watchID, known: known([stored("1", price: 50)]),
                                   incoming: [raw("1", price: 50)],
                                   baselineDone: true, now: now)

        XCTAssertTrue(result.priceDrops.isEmpty)
        XCTAssertTrue(result.pricePoints.isEmpty)
    }

    func testAnOldPriceDropIsNotAnnouncedAgain() {
        // The listing already carries the trace of a drop detected earlier.
        let previouslyDropped = stored("1", price: 55, previousPrice: 70,
                                       priceDroppedAt: now.addingTimeInterval(-60))

        let result = ScanDiff.diff(watchID: watchID, known: known([previouslyDropped]),
                                   incoming: [raw("1", price: 55)],
                                   baselineDone: true, now: now)

        XCTAssertTrue(result.priceDrops.isEmpty)
        // …but the card still shows it.
        XCTAssertEqual(result.listings.first?.previousPrice, 70)
    }

    func testRecordsAPricePointWheneverThePriceChanges() {
        let result = ScanDiff.diff(watchID: watchID, known: known([stored("1", price: 70)]),
                                   incoming: [raw("1", price: 55)],
                                   baselineDone: true, now: now)

        XCTAssertEqual(result.pricePoints.count, 1)
        XCTAssertEqual(result.pricePoints.first?.point.price, 55)
        XCTAssertEqual(result.pricePoints.first?.itemID, "1")
    }

    // MARK: - Defensive merging

    func testAScanWithoutAPriceKeepsTheKnownPrice() {
        let result = ScanDiff.diff(watchID: watchID, known: known([stored("1", price: 45)]),
                                   incoming: [raw("1", price: nil)],
                                   baselineDone: true, now: now)

        XCTAssertEqual(result.listings.first?.price, 45)
        XCTAssertTrue(result.priceDrops.isEmpty)
    }

    func testMissingFieldsNeverEraseStoredValues() {
        let result = ScanDiff.diff(
            watchID: watchID, known: known([stored("1")]),
            incoming: [raw("1", title: nil, brand: nil, size: nil, imageURL: nil)],
            baselineDone: true, now: now
        )

        let entity = try! XCTUnwrap(result.listings.first)
        XCTAssertEqual(entity.title, "Nike Air Max 95")
        XCTAssertEqual(entity.brand, "Nike")
        XCTAssertEqual(entity.size, "42")
        XCTAssertEqual(entity.imageURL, "https://img/x.jpg")
    }

    func testFavouriteAndFirstSeenSurviveARescan() {
        let firstSeen = now.addingTimeInterval(-999)
        let result = ScanDiff.diff(
            watchID: watchID,
            known: known([stored("1", isFavorite: true, firstSeenAt: firstSeen)]),
            incoming: [raw("1", price: 30)], baselineDone: true, now: now
        )

        let entity = try! XCTUnwrap(result.listings.first)
        XCTAssertTrue(entity.isFavorite)
        XCTAssertEqual(entity.firstSeenAt, firstSeen)
        XCTAssertEqual(entity.lastSeenAt, now)
    }

    func testADuplicatedIDInOneResponseIsStoredOnce() {
        let result = ScanDiff.diff(watchID: watchID, known: [:],
                                   incoming: [raw("1"), raw("1"), raw("2")],
                                   baselineDone: true, now: now)
        XCTAssertEqual(result.listings.count, 2)
    }

    func testBlankIDsAreIgnored() {
        let result = ScanDiff.diff(watchID: watchID, known: [:],
                                   incoming: [raw(""), raw("  "), raw("3")],
                                   baselineDone: true, now: now)
        XCTAssertEqual(result.listings.map(\.itemID), ["3"])
    }

    func testAnEmptyResponseChangesNothing() {
        let result = ScanDiff.diff(watchID: watchID, known: known([stored("1")]),
                                   incoming: [], baselineDone: true, now: now)
        XCTAssertTrue(result.isEmpty)
        XCTAssertTrue(result.newListings.isEmpty)
        XCTAssertTrue(result.priceDrops.isEmpty)
    }

    func testAListingThatDisappearsIsLeftUntouched() {
        // Vinted stops returning "1"; RadarDeal keeps what it knows rather than deleting it.
        let result = ScanDiff.diff(watchID: watchID,
                                   known: known([stored("1"), stored("2")]),
                                   incoming: [raw("2")], baselineDone: true, now: now)
        XCTAssertEqual(result.listings.map(\.itemID), ["2"])
    }
}
